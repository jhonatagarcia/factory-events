package br.com.factory.events.service;

import br.com.factory.events.exception.ManualRequestConflictException;
import br.com.factory.events.model.dto.CreateEventRequest;
import br.com.factory.events.model.dto.CreateManualEventRequest;
import br.com.factory.events.model.dto.EventResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

@Service
public class ManualEventService {
    private final JdbcTemplate jdbc;
    private final EventService events;

    public ManualEventService(JdbcTemplate jdbc, EventService events) {
        this.jdbc = jdbc;
        this.events = events;
    }

    // A preview never reserves a number. Another user may register first.
    @Transactional(readOnly = true)
    public String nextId() {
        return format(nextAvailable(false));
    }

    public record RegistrationResult(EventResponse event, boolean replay) { }

    @Transactional
    public RegistrationResult create(CreateManualEventRequest request) {
        // The row lock serializes manual registrations across application instances.
        long number = nextAvailable(true);
        String hash = hash(request);
        var previous = jdbc.queryForList(
                "SELECT event_id, payload_hash FROM manual_event_requests WHERE request_id = ?",
                request.requestId());
        if (!previous.isEmpty()) {
            var saved = previous.getFirst();
            if (!hash.equals(saved.get("payload_hash"))) {
                throw new ManualRequestConflictException();
            }
            return new RegistrationResult(events.findById((String) saved.get("event_id")), true);
        }

        String eventId = format(number);
        EventResponse result = events.create(new CreateEventRequest(
                eventId, request.eventType(), request.description(), request.timestamp()));
        jdbc.update("INSERT INTO manual_event_requests (request_id, event_id, payload_hash) VALUES (?, ?, ?)",
                request.requestId(), eventId, hash);
        jdbc.update("UPDATE manual_event_counter SET next_value = ? WHERE id = 1", number + 1);
        return new RegistrationResult(result, false);
    }

    private long nextAvailable(boolean lock) {
        Long counter = jdbc.queryForObject("SELECT next_value FROM manual_event_counter WHERE id = 1"
                + (lock ? " FOR UPDATE" : ""), Long.class);
        // Include numeric IDs already received from external equipment.
        Long maximum = jdbc.queryForObject("""
                SELECT COALESCE(MAX(SUBSTRING(event_id FROM 5)::BIGINT), 0)
                FROM events WHERE event_id ~ '^EVT-[0-9]{1,12}$'
                """, Long.class);
        return Math.max(counter, maximum + 1);
    }

    private String format(long number) {
        return "EVT-" + String.format(java.util.Locale.ROOT, "%05d", number);
    }

    private String hash(CreateManualEventRequest request) {
        String canonical = request.eventType().name() + "\n" + request.timestamp().toInstant()
                + "\n" + request.description();
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 indisponível", exception);
        }
    }
}

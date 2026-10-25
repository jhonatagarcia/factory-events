package br.com.factory.events.service;

import br.com.factory.events.entity.EventAuditEntity;
import br.com.factory.events.entity.EventEntity;
import br.com.factory.events.exception.EventNotFoundException;
import br.com.factory.events.model.dto.CreateEventRequest;
import br.com.factory.events.model.dto.EventPageResponse;
import br.com.factory.events.model.dto.EventResponse;
import br.com.factory.events.repository.EventAuditRepository;
import br.com.factory.events.repository.EventInsertRepository;
import br.com.factory.events.repository.EventRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

@Service
public class EventService {

    private final EventRepository eventRepository;
    private final EventInsertRepository eventInsertRepository;
    private final EventAuditRepository eventAuditRepository;
    private final Clock clock;

    public EventService(
            EventRepository eventRepository,
            EventInsertRepository eventInsertRepository,
            EventAuditRepository eventAuditRepository,
            Clock clock
    ) {
        this.eventRepository = eventRepository;
        this.eventInsertRepository = eventInsertRepository;
        this.eventAuditRepository = eventAuditRepository;
        this.clock = clock;
    }

    @Transactional
    public EventResponse create(CreateEventRequest request) {
        Instant receivedAt = Instant.now(clock);

        EventEntity event = new EventEntity(
                request.eventId(),
                request.eventType(),
                request.description(),
                request.timestamp().toInstant(),
                receivedAt
        );

        eventInsertRepository.insert(event);

        switch (request.eventType()) {
            case PRODUCTION -> {
                // O registro do evento já atende à regra.
            }

            case QUALITY -> {
                EventAuditEntity audit = new EventAuditEntity(
                        event.getEventId(),
                        Instant.now(clock)
                );

                eventAuditRepository.saveAndFlush(audit);
            }
        }

        return EventResponse.from(event);
    }

    @Transactional(readOnly = true)
    public EventResponse findById(String eventId) {
        EventEntity event = eventRepository.findById(eventId)
                .orElseThrow(EventNotFoundException::new);

        return EventResponse.from(event);
    }

    @Transactional(readOnly = true)
    public EventPageResponse findAll(int page, int size) {
        Sort sort = Sort.by(
                Sort.Order.desc("receivedAt"),
                Sort.Order.asc("eventId")
        );

        PageRequest pageable = PageRequest.of(page, size, sort);

        Page<EventEntity> result =
                eventRepository.findAll(pageable);

        return EventPageResponse.from(result);
    }
}

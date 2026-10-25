package br.com.factory.events.entity;

import br.com.factory.events.enums.EventType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "events")
public class EventEntity {
    @Id
    @Column(
            name = "event_id",
            nullable = false,
            length = 100,
            updatable = false
    )
    private String eventId;

    @Enumerated(EnumType.STRING)
    @Column(
            name = "event_type",
            nullable = false,
            length = 20,
            updatable = false
    )
    private EventType eventType;

    @Column(
            name = "description",
            nullable = false,
            length = 2000,
            updatable = false
    )
    private String description;

    @Column(
            name = "occurred_at",
            nullable = false,
            updatable = false
    )
    private Instant occurredAt;

    @Column(
            name = "received_at",
            nullable = false,
            updatable = false
    )
    private Instant receivedAt;

    protected EventEntity() {
    }

    public EventEntity(
            String eventId,
            EventType eventType,
            String description,
            Instant occurredAt,
            Instant receivedAt
    ) {
        this.eventId = eventId;
        this.eventType = eventType;
        this.description = description;
        this.occurredAt = occurredAt;
        this.receivedAt = receivedAt;
    }

    public String getEventId() {return eventId;}

    public EventType getEventType() {
        return eventType;
    }

    public String getDescription() {
        return description;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }

}

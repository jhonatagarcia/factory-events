package br.com.factory.events.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "event_audits")
public class EventAuditEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(
            name = "event_id",
            nullable = false,
            unique = true,
            length = 100,
            updatable = false
    )
    private String eventId;

    @Column(
            name = "action",
            nullable = false,
            length = 50,
            updatable = false
    )
    private String action;

    @Column(
            name = "created_at",
            nullable = false,
            updatable = false
    )
    private Instant createdAt;

    protected EventAuditEntity() {
    }

    public EventAuditEntity(String eventId, Instant createdAt) {
        this.eventId = eventId;
        this.action = "QUALITY_EVENT_REGISTERED";
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public String getEventId() {
        return eventId;
    }

    public String getAction() {
        return action;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}

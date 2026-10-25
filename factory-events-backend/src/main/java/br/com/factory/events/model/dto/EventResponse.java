package br.com.factory.events.model.dto;

import br.com.factory.events.entity.EventEntity;
import br.com.factory.events.enums.EventType;

import java.time.Instant;

public record EventResponse (
        String eventId,
        EventType eventType,
        String description,
        Instant timestamp,
        Instant receivedAt
){
    public static EventResponse from(EventEntity event) {
        return new EventResponse(
                event.getEventId(),
                event.getEventType(),
                event.getDescription(),
                event.getOccurredAt(),
                event.getReceivedAt()
        );
    }
}

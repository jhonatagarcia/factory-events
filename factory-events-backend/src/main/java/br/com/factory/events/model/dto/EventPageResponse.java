package br.com.factory.events.model.dto;

import br.com.factory.events.entity.EventEntity;
import org.springframework.data.domain.Page;

import java.util.List;

public record EventPageResponse(
        List<EventResponse> items,
        int page,
        int size,
        long totalElements,
        int totalPages
) {
    public static EventPageResponse from(Page<EventEntity> result) {
        return new EventPageResponse(
                result.getContent().stream().map(EventResponse::from).toList(),
                result.getNumber(),
                result.getSize(),
                result.getTotalElements(),
                result.getTotalPages()
        );
    }
}

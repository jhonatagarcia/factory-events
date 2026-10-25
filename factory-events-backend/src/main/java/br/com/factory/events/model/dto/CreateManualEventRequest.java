package br.com.factory.events.model.dto;

import br.com.factory.events.enums.EventType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.UUID;

public record CreateManualEventRequest(
        @NotNull UUID requestId,
        @NotNull EventType eventType,
        @NotBlank @Size(max = 2000) String description,
        @NotNull OffsetDateTime timestamp
) { }

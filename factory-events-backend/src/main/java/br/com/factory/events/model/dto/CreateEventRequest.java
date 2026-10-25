package br.com.factory.events.model.dto;

import br.com.factory.events.enums.EventType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;

public record CreateEventRequest (
        @NotBlank(message = "O identificador do evento é obrigatório.")
        @Size(
                max = 100,
                message = "O identificador deve ter até 100 caracteres."
        )
        @Pattern(
                regexp = "[A-Za-z0-9][A-Za-z0-9._-]*",
                message = "Use letras, números, ponto, hífen ou sublinhado no identificador."
        )
        String eventId,

        @NotNull(message = "O tipo do evento é obrigatório.")
        EventType eventType,

        @NotBlank(message = "A descrição é obrigatória.")
        @Size(
                max = 2000,
                message = "A descrição deve ter até 2000 caracteres."
        )
        String description,

        @NotNull(message = "A data e hora do evento são obrigatórias.")
        OffsetDateTime timestamp
){}

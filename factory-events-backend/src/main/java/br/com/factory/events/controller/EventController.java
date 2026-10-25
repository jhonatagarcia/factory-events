package br.com.factory.events.controller;

import br.com.factory.events.model.dto.CreateEventRequest;
import br.com.factory.events.model.dto.EventPageResponse;
import br.com.factory.events.model.dto.EventResponse;
import br.com.factory.events.service.EventService;
import br.com.factory.events.service.ManualEventService;
import br.com.factory.events.model.dto.CreateManualEventRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.http.ResponseEntity;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import io.micrometer.core.instrument.MeterRegistry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.http.ProblemDetail;

@Tag(name = "Eventos", description = "Registro e consulta de ocorrências industriais")
@ApiResponses({
    @ApiResponse(responseCode = "400", description = "Payload ou parâmetros inválidos", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
    @ApiResponse(responseCode = "404", description = "Evento não encontrado", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
    @ApiResponse(responseCode = "409", description = "ID duplicado ou requestId reutilizado com outros dados", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
    @ApiResponse(responseCode = "500", description = "Falha interna; consulte os logs pelo correlationId", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
})
@RestController
@RequestMapping("/api/events")
public class EventController {

    private static final Logger log =
            LoggerFactory.getLogger(EventController.class);

    private final EventService eventService;

    private final ManualEventService manualEvents;

    private final MeterRegistry metrics;

    public EventController(EventService eventService, ManualEventService manualEvents, MeterRegistry metrics) {
        this.eventService = eventService;
        this.manualEvents = manualEvents;
        this.metrics = metrics;
    }

    public record NextEventIdResponse(String eventId) { }

    @Operation(summary = "Consultar prévia do próximo ID manual", description = "Não reserva o número; ele pode mudar com cadastros concorrentes.")
    @GetMapping("/manual/next-id")
    public NextEventIdResponse nextManualId() {
        return new NextEventIdResponse(manualEvents.nextId());
    }

    @Operation(summary = "Cadastrar evento manual", description = "Gera ID no banco. Repetir requestId e conteúdo retorna o evento original, sem novo registro.")
    @ApiResponse(responseCode = "201", description = "Evento criado ou tentativa já confirmada")
    @PostMapping("/manual")
    public ResponseEntity<EventResponse> createManual(
            @Valid @RequestBody CreateManualEventRequest request) {
        var registration = manualEvents.create(request);
        EventResponse result = registration.event();
        if (registration.replay()) {
            metrics.counter("factory.events.replays", "source", "manual").increment();
        } else {
            recordRegistration(result, "manual");
        }
        URI location = ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/api/events/{eventId}").buildAndExpand(result.eventId()).toUri();
        log.info("Cadastro manual concluído: eventId={}", result.eventId());
        return ResponseEntity.created(location).body(result);
    }

    @Operation(summary = "Receber evento de equipamento", description = "O eventId pertence à origem e deve ser preservado em reenvios. IDs existentes retornam 409.")
    @ApiResponse(responseCode = "201", description = "Evento registrado")
    @PostMapping
    public ResponseEntity<EventResponse> create(
            @Valid @RequestBody CreateEventRequest request
    ) {
        EventResponse response = eventService.create(request);
        recordRegistration(response, "equipment");

        URI location = ServletUriComponentsBuilder
                .fromCurrentRequest()
                .path("/{eventId}")
                .buildAndExpand(response.eventId())
                .toUri();

        log.info(
                "Evento registrado: eventId={}, eventType={}",
                response.eventId(),
                response.eventType()
        );

        return ResponseEntity
                .created(location)
                .body(response);
    }

    @Operation(summary = "Consultar evento por identificador")
    @GetMapping("/{eventId}")
    public EventResponse findById(
            @PathVariable String eventId
    ) {
        return eventService.findById(eventId);
    }

    @Operation(summary = "Listar eventos paginados", description = "Página inicial 0; size entre 1 e 100. Ordem: recebimento decrescente e ID crescente.")
    @GetMapping
    public EventPageResponse findAll(
            @RequestParam(defaultValue = "0")
            @Min(0)
            int page,

            @RequestParam(defaultValue = "20")
            @Min(1)
            @Max(100)
            int size
    ) {
        return eventService.findAll(page, size);
    }

    private void recordRegistration(EventResponse event, String source) {
        metrics.counter("factory.events.registered", "type", event.eventType().name(), "source", source).increment();
    }
}

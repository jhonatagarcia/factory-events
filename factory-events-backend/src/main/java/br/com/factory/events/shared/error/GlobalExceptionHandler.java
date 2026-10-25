package br.com.factory.events.shared.error;

import br.com.factory.events.exception.EventNotFoundException;
import br.com.factory.events.exception.ManualRequestConflictException;
import org.hibernate.exception.ConstraintViolationException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.dao.DataIntegrityViolationException;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

import org.springframework.http.converter.HttpMessageNotReadableException;

import org.springframework.web.bind.MethodArgumentNotValidException;

import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import org.springframework.web.context.request.WebRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.ErrorResponse;

import org.springframework.web.method.annotation.HandlerMethodValidationException;

import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.List;
import io.micrometer.core.instrument.MeterRegistry;

@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler{

    private static final Logger log =
            LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final MeterRegistry metrics;

    public GlobalExceptionHandler(MeterRegistry metrics) {
        this.metrics = metrics;
    }

    public record FieldErrorResponse(
            String field,
            String message
    ) {
    }

    @ExceptionHandler(ManualRequestConflictException.class)
    public ProblemDetail handleManualConflict(ManualRequestConflictException exception) {
        return problem(HttpStatus.CONFLICT, "Tentativa já utilizada", exception.getMessage(),
                "MANUAL_REQUEST_CONFLICT");
    }

    @ExceptionHandler(EventNotFoundException.class)
    public ProblemDetail handleNotFound(
            EventNotFoundException exception
    ) {
        return problem(
                HttpStatus.NOT_FOUND,
                "Evento não encontrado",
                exception.getMessage(),
                "EVENT_NOT_FOUND"
        );
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ProblemDetail handleDataIntegrity(
            DataIntegrityViolationException exception
    ) {
        if (isEventPrimaryKeyViolation(exception)) {
            metrics.counter("factory.events.duplicates").increment();
            log.info("Cadastro rejeitado por identificador duplicado.");

            return problem(
                    HttpStatus.CONFLICT,
                    "Evento já registrado",
                    "Já existe um evento com esse identificador.",
                    "EVENT_ALREADY_EXISTS"
            );
        }

        log.error("Falha de integridade ao persistir dados.", exception);

        return problem(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Erro interno",
                "Não foi possível concluir a operação.",
                "INTERNAL_ERROR"
        );
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException exception,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request
    ) {
        List<FieldErrorResponse> errors =
                exception.getBindingResult()
                        .getFieldErrors()
                        .stream()
                        .map(error -> new FieldErrorResponse(
                                error.getField(),
                                error.getDefaultMessage()
                        ))
                        .toList();

        ProblemDetail body = problem(
                HttpStatus.BAD_REQUEST,
                "Dados inválidos",
                "Revise os campos informados.",
                "VALIDATION_ERROR"
        );

        body.setProperty("errors", errors);

        return handleExceptionInternal(
                exception,
                body,
                headers,
                HttpStatus.BAD_REQUEST,
                request
        );
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException exception,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request
    ) {
        ProblemDetail body = problem(
                HttpStatus.BAD_REQUEST,
                "Corpo da requisição inválido",
                "Verifique o JSON, o tipo do evento e o timestamp com offset.",
                "INVALID_REQUEST_BODY"
        );

        return handleExceptionInternal(
                exception,
                body,
                headers,
                HttpStatus.BAD_REQUEST,
                request
        );
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(
            HandlerMethodValidationException exception,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request
    ) {
        ProblemDetail body = problem(
                HttpStatus.BAD_REQUEST,
                "Parâmetros inválidos",
                "A página deve ser maior ou igual a zero, e o tamanho entre 1 e 100.",
                "INVALID_PARAMETERS"
        );

        return handleExceptionInternal(
                exception,
                body,
                headers,
                HttpStatus.BAD_REQUEST,
                request
        );
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception exception) {
        log.error("Falha inesperada ao processar requisição.", exception);

        return problem(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Erro interno",
                "Não foi possível concluir a operação.",
                "INTERNAL_ERROR"
        );
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception exception, Object body, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        ProblemDetail detail = body instanceof ProblemDetail existing ? existing
                : exception instanceof ErrorResponse error ? error.getBody()
                : ProblemDetail.forStatus(status);
        if (detail.getProperties() == null || !detail.getProperties().containsKey("code")) {
            String code = switch (status.value()) {
                case 400 -> "INVALID_PARAMETERS";
                case 404 -> "RESOURCE_NOT_FOUND";
                case 405 -> "METHOD_NOT_ALLOWED";
                case 406 -> "NOT_ACCEPTABLE";
                case 415 -> "UNSUPPORTED_MEDIA_TYPE";
                default -> status.is5xxServerError() ? "INTERNAL_ERROR" : "HTTP_ERROR";
            };
            detail.setProperty("code", code);
            detail.setDetail(switch (status.value()) {
                case 400 -> "Revise os parâmetros informados.";
                case 404 -> "O recurso solicitado não existe.";
                case 405 -> "Método HTTP não permitido para este recurso.";
                case 406 -> "O formato de resposta solicitado não é suportado.";
                case 415 -> "Envie o corpo com Content-Type application/json.";
                default -> "Não foi possível concluir a requisição.";
            });
        }
        detail.setProperty("correlationId", request.getAttribute("correlationId", RequestAttributes.SCOPE_REQUEST));
        return super.handleExceptionInternal(exception, detail, headers, status, request);
    }

    private boolean isEventPrimaryKeyViolation(Throwable exception) {
        Throwable current = exception;

        while (current != null) {
            if (current instanceof ConstraintViolationException violation) {
                return "23505".equals(violation.getSQLState())
                        && "pk_events".equals(violation.getConstraintName());
            }

            current = current.getCause();
        }

        return false;
    }

    private ProblemDetail problem(
            HttpStatus status,
            String title,
            String detail,
            String code
    ) {
        ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(status, detail);

        problem.setTitle(title);
        problem.setProperty("code", code);
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            problem.setProperty("correlationId", attributes.getRequest().getAttribute("correlationId"));
        }
        return problem;
    }
}

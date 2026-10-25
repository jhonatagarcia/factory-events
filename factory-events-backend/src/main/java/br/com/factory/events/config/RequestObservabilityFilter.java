package br.com.factory.events.config;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestObservabilityFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(RequestObservabilityFilter.class);
    public static final String HEADER = "X-Correlation-ID";
    private final MeterRegistry metrics;

    public RequestObservabilityFilter(MeterRegistry metrics) {
        this.metrics = metrics;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String supplied = request.getHeader(HEADER);
        String id = supplied != null && supplied.matches("[A-Za-z0-9._-]{1,64}")
                ? supplied : UUID.randomUUID().toString();
        MDC.put("correlationId", id);
        request.setAttribute("correlationId", id);
        response.setHeader(HEADER, id);
        long started = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            try {
                if (request.getRequestURI().startsWith("/api/")) {
                    int status = response.getStatus();
                    // No IDs, descriptions or raw paths as metric tags (bounded cardinality).
                    String method = switch (request.getMethod()) {
                        case "GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS" -> request.getMethod();
                        default -> "OTHER";
                    };
                    metrics.counter("factory.api.requests", "method", method,
                            "outcome", status >= 500 ? "server_error" : status >= 400 ? "client_error" : "success").increment();
                    log.info("HTTP method={} status={} durationMs={}", request.getMethod(), status,
                            (System.nanoTime() - started) / 1_000_000);
                }
            } finally {
                MDC.remove("correlationId");
            }
        }
    }
}

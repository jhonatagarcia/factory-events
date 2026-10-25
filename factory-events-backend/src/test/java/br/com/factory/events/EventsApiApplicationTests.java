package br.com.factory.events;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class EventsApiApplicationTests {

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18")
            .withDatabaseName("events_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    io.micrometer.core.instrument.MeterRegistry metrics;

    private final HttpClient client = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();

    @BeforeEach
    void cleanDatabase() {
        // Only the isolated Testcontainers database is configured in this context.
        jdbc.execute("TRUNCATE TABLE manual_event_requests, event_audits, events RESTART IDENTITY");
        jdbc.update("UPDATE manual_event_counter SET next_value = 1 WHERE id = 1");
    }

    @Test
    void registersProductionAndReturnsItsLocation() throws Exception {
        var response = post(payload("EVT-PRODUCTION", "PRODUCTION"));
        assertEquals(201, response.statusCode());
        assertEquals(1L, count("events"));
        assertEquals(0L, count("event_audits"));
        JsonNode body = json.readTree(response.body());
        assertEquals("EVT-PRODUCTION", body.path("eventId").asText());
        assertEquals(Instant.parse("2026-05-20T13:00:00Z"),
                Instant.parse(body.path("timestamp").asText()));
        var location = response.headers().firstValue("Location").orElseThrow();
        var lookup = get(URI.create(location));
        assertEquals(200, lookup.statusCode());
        assertEquals("EVT-PRODUCTION", json.readTree(lookup.body()).path("eventId").asText());
    }

    @Test
    void registersQualityAndItsAudit() throws Exception {
        assertEquals(201, post(payload("EVT-QUALITY", "QUALITY")).statusCode());
        assertEquals(1L, count("events"));
        assertEquals(1L, count("event_audits"));
        assertEquals("QUALITY_EVENT_REGISTERED", jdbc.queryForObject(
                "SELECT action FROM event_audits WHERE event_id = ?",
                String.class, "EVT-QUALITY"));
    }

    @Test
    void rejectsDuplicateEvenWithChangedContentWithoutUpdatingOriginal() throws Exception {
        assertEquals(201, post(payload("EVT-DUPLICATE", "QUALITY")).statusCode());
        var duplicate = post(payload("EVT-DUPLICATE", "PRODUCTION")
                .replace("Evento de teste", "Alterado"));
        assertEquals(409, duplicate.statusCode());
        assertEquals("EVENT_ALREADY_EXISTS", json.readTree(duplicate.body()).path("code").asText());
        assertEquals(1L, count("events"));
        assertEquals(1L, count("event_audits"));
        assertEquals("Evento de teste", jdbc.queryForObject(
                "SELECT description FROM events WHERE event_id = ?", String.class, "EVT-DUPLICATE"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}",
            "{broken",
            "{\"eventId\":\"EVT-1\",\"eventType\":\"UNKNOWN\"}",
            "{\"eventId\":\"EVT-1\",\"eventType\":\"PRODUCTION\",\"description\":\"ok\",\"timestamp\":\"2026-05-20T10:00:00\"}"
    })
    void rejectsInvalidPayloads(String payload) throws Exception {
        assertEquals(400, post(payload).statusCode());
        assertEquals(0L, count("events"));
        assertEquals(0L, count("event_audits"));
    }

    @Test
    void returnsFieldValidationErrors() throws Exception {
        var response = post(payload("EVT-VALIDATION", "PRODUCTION")
                .replace("Evento de teste", "   "));
        assertEquals(400, response.statusCode());
        JsonNode body = json.readTree(response.body());
        assertEquals("VALIDATION_ERROR", body.path("code").asText());
        assertEquals("description", body.path("errors").get(0).path("field").asText());
    }

    @Test
    void returnsNotFoundForMissingEvent() throws Exception {
        var response = get("/api/events/MISSING");
        assertEquals(404, response.statusCode());
        assertEquals("EVENT_NOT_FOUND", json.readTree(response.body()).path("code").asText());
    }

    @Test
    void returnsEmptyPage() throws Exception {
        var response = get("/api/events?page=0&size=20");
        assertEquals(200, response.statusCode());
        JsonNode body = json.readTree(response.body());
        assertEquals(0, body.path("items").size());
        assertEquals(0, body.path("totalElements").asInt());
    }

    @Test
    void paginatesInDeterministicOrder() throws Exception {
        assertEquals(201, post(payload("EVT-A", "PRODUCTION")).statusCode());
        assertEquals(201, post(payload("EVT-B", "PRODUCTION")).statusCode());
        // Force a tie to also exercise the eventId tie-breaker.
        jdbc.update("UPDATE events SET received_at = TIMESTAMPTZ '2026-05-20 13:00:00+00'");
        var response = get("/api/events?page=1&size=1");
        assertEquals(200, response.statusCode());
        JsonNode body = json.readTree(response.body());
        assertEquals(2, body.path("totalElements").asInt());
        assertEquals(2, body.path("totalPages").asInt());
        assertEquals(1, body.path("page").asInt());
        assertEquals("EVT-B", body.path("items").get(0).path("eventId").asText());
    }

    @ParameterizedTest
    @ValueSource(strings = {"page=-1", "size=0", "size=101", "page=abc"})
    void rejectsInvalidPagination(String query) throws Exception {
        assertEquals(400, get("/api/events?" + query).statusCode());
    }

    @Test
    void rollsBackEventWhenAuditInsertFails() throws Exception {
        // Inject a real database failure only in this disposable test database.
        jdbc.execute("""
                ALTER TABLE event_audits ADD CONSTRAINT ck_test_reject_audit
                CHECK (event_id <> 'EVT-ROLLBACK')
                """);
        try {
            var response = post(payload("EVT-ROLLBACK", "QUALITY"));
            assertEquals(500, response.statusCode());
            assertEquals("INTERNAL_ERROR", json.readTree(response.body()).path("code").asText());
            assertFalse(response.body().contains("ck_test_reject_audit"));
            assertEquals(0L, count("events"));
            assertEquals(0L, count("event_audits"));
        } finally {
            jdbc.execute("ALTER TABLE event_audits DROP CONSTRAINT ck_test_reject_audit");
        }
    }

    @Test
    void preventsConcurrentQualityDuplicates() throws Exception {
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<Integer> send = () -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Timed out waiting for start");
                }
                return post(payload("EVT-CONCURRENT", "QUALITY")).statusCode();
            };
            var first = executor.submit(send);
            var second = executor.submit(send);
            try {
                assertTrue(ready.await(10, TimeUnit.SECONDS));
            } finally {
                start.countDown();
            }
            int a = first.get(20, TimeUnit.SECONDS);
            int b = second.get(20, TimeUnit.SECONDS);
            assertTrue((a == 201 && b == 409) || (a == 409 && b == 201),
                    "Expected 201 and 409, got " + a + " and " + b);
        }
        assertEquals(1L, count("events"));
        assertEquals(1L, count("event_audits"));
    }

    @Test
    void manualRegistrationIncrementsAndReplaysWithoutDuplicates() throws Exception {
        String request = manualPayload(java.util.UUID.randomUUID());
        assertEquals("EVT-00001", json.readTree(get("/api/events/manual/next-id").body()).path("eventId").asText());
        var first = postAt("/api/events/manual", request);
        assertEquals(201, first.statusCode());
        assertEquals("EVT-00001", json.readTree(first.body()).path("eventId").asText());
        assertEquals(200, get(URI.create(first.headers().firstValue("Location").orElseThrow())).statusCode());
        var replay = postAt("/api/events/manual", request);
        assertEquals(201, replay.statusCode());
        assertEquals("EVT-00001", json.readTree(replay.body()).path("eventId").asText());
        assertEquals("EVT-00002", json.readTree(get("/api/events/manual/next-id").body()).path("eventId").asText());
        assertEquals(1L, count("events"));
        assertEquals(1L, count("event_audits"));
    }

    @Test
    void manualRegistrationStartsAfterExistingNumericIds() throws Exception {
        assertEquals(201, post(payload("EVT-00003", "PRODUCTION")).statusCode());
        var result = postAt("/api/events/manual", manualPayload(java.util.UUID.randomUUID()));
        assertEquals(201, result.statusCode());
        assertEquals("EVT-00004", json.readTree(result.body()).path("eventId").asText());
    }

    @Test
    void manualRequestCannotBeReusedForDifferentContent() throws Exception {
        String request = manualPayload(java.util.UUID.randomUUID());
        assertEquals(201, postAt("/api/events/manual", request).statusCode());
        var result = postAt("/api/events/manual", request.replace("Evento manual", "Outro evento"));
        assertEquals(409, result.statusCode());
        assertEquals("MANUAL_REQUEST_CONFLICT", json.readTree(result.body()).path("code").asText());
        assertEquals(1L, count("events"));
    }

    @Test
    void concurrentManualRegistrationsGetDistinctSequentialIds() throws Exception {
        try (var executor = Executors.newFixedThreadPool(2)) {
            var gate = new CountDownLatch(1);
            java.util.concurrent.Callable<String> send = () -> {
                assertTrue(gate.await(10, TimeUnit.SECONDS));
                var result = postAt("/api/events/manual", manualPayload(java.util.UUID.randomUUID()));
                assertEquals(201, result.statusCode());
                return json.readTree(result.body()).path("eventId").asText();
            };
            var a = executor.submit(send);
            var b = executor.submit(send);
            gate.countDown();
            assertEquals(java.util.Set.of("EVT-00001", "EVT-00002"),
                    java.util.Set.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS)));
        }
        assertEquals(2L, count("events"));
        assertEquals(2L, count("event_audits"));
    }

    @Test
    void manualAuditFailureRollsBackCounterAndRequest() throws Exception {
        jdbc.execute("ALTER TABLE event_audits ADD CONSTRAINT ck_manual_failure CHECK (event_id <> 'EVT-00001')");
        String request = manualPayload(java.util.UUID.randomUUID());
        try {
            assertEquals(500, postAt("/api/events/manual", request).statusCode());
            assertEquals(0L, count("events"));
            assertEquals(0L, count("manual_event_requests"));
            assertEquals("EVT-00001", json.readTree(get("/api/events/manual/next-id").body()).path("eventId").asText());
        } finally {
            jdbc.execute("ALTER TABLE event_audits DROP CONSTRAINT ck_manual_failure");
        }
        assertEquals(201, postAt("/api/events/manual", request).statusCode());
    }

    @Test
    void concurrentSameManualRequestCreatesOnlyOneEvent() throws Exception {
        String request = manualPayload(java.util.UUID.randomUUID());
        try (var executor = Executors.newFixedThreadPool(2)) {
            var gate = new CountDownLatch(1);
            java.util.concurrent.Callable<String> send = () -> {
                assertTrue(gate.await(10, TimeUnit.SECONDS));
                var result = postAt("/api/events/manual", request);
                assertEquals(201, result.statusCode());
                return json.readTree(result.body()).path("eventId").asText();
            };
            var a = executor.submit(send);
            var b = executor.submit(send);
            gate.countDown();
            assertEquals("EVT-00001", a.get(20, TimeUnit.SECONDS));
            assertEquals("EVT-00001", b.get(20, TimeUnit.SECONDS));
        }
        assertEquals(1L, count("events"));
        assertEquals(1L, count("event_audits"));
        assertEquals(1L, count("manual_event_requests"));
    }

    @Test
    void propagatesCorrelationIdOnErrorsWithoutLeakingItToNextRequest() throws Exception {
        var request = HttpRequest.newBuilder(uri("/api/events/MISSING"))
                .header("X-Correlation-ID", "interview-test-123").GET().build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(404, response.statusCode());
        assertEquals("interview-test-123", response.headers().firstValue("X-Correlation-ID").orElseThrow());
        assertEquals("interview-test-123", json.readTree(response.body()).path("correlationId").asText());
        var next = get("/api/events/MISSING");
        assertNotEquals("interview-test-123", next.headers().firstValue("X-Correlation-ID").orElseThrow());
    }

    @Test
    void replacesInvalidCorrelationId() throws Exception {
        var response = client.send(HttpRequest.newBuilder(uri("/api/events"))
                .header("X-Correlation-ID", "invalid value with spaces").GET().build(),
                HttpResponse.BodyHandlers.ofString());
        String id = response.headers().firstValue("X-Correlation-ID").orElseThrow();
        assertDoesNotThrow(() -> java.util.UUID.fromString(id));
    }

    @Test
    void standardizesLessCommonHttpErrors() throws Exception {
        var method = client.send(HttpRequest.newBuilder(uri("/api/events"))
                .PUT(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(405, method.statusCode());
        assertEquals("METHOD_NOT_ALLOWED", json.readTree(method.body()).path("code").asText());
        assertTrue(method.headers().firstValue("Allow").orElseThrow().contains("POST"));
        var media = client.send(HttpRequest.newBuilder(uri("/api/events"))
                .header("Content-Type", "text/plain").POST(HttpRequest.BodyPublishers.ofString("invalid")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(415, media.statusCode());
        assertEquals("UNSUPPORTED_MEDIA_TYPE", json.readTree(media.body()).path("code").asText());
        var missing = get("/api/unknown/path");
        assertEquals(404, missing.statusCode());
        assertEquals("RESOURCE_NOT_FOUND", json.readTree(missing.body()).path("code").asText());
        var parameter = get("/api/events?page=abc");
        assertEquals(400, parameter.statusCode());
        assertEquals("INVALID_PARAMETERS", json.readTree(parameter.body()).path("code").asText());
        assertTrue(json.readTree(parameter.body()).hasNonNull("correlationId"));
    }

    @Test
    void exposesOpenApiAndSwaggerUi() throws Exception {
        var docs = get("/v3/api-docs");
        assertEquals(200, docs.statusCode());
        JsonNode schema = json.readTree(docs.body());
        assertTrue(schema.path("paths").has("/api/events/manual"));
        assertTrue(schema.path("paths").path("/api/events").path("post").path("responses").has("201"));
        assertEquals(200, get("/swagger-ui/index.html").statusCode());
    }

    @Test
    void metricsCountConfirmedEventsAndSeparateReplaysAndDuplicates() throws Exception {
        double createdBefore = metricTotal("factory.events.registered");
        double replaysBefore = metricTotal("factory.events.replays");
        double duplicatesBefore = metricTotal("factory.events.duplicates");
        String request = manualPayload(java.util.UUID.randomUUID());
        assertEquals(201, postAt("/api/events/manual", request).statusCode());
        assertEquals(201, postAt("/api/events/manual", request).statusCode());
        assertEquals(createdBefore + 1, metricTotal("factory.events.registered"));
        assertEquals(replaysBefore + 1, metricTotal("factory.events.replays"));
        assertEquals(201, post(payload("METRIC-EQUIPMENT", "PRODUCTION")).statusCode());
        assertEquals(409, post(payload("METRIC-EQUIPMENT", "PRODUCTION")).statusCode());
        assertEquals(createdBefore + 2, metricTotal("factory.events.registered"));
        assertEquals(duplicatesBefore + 1, metricTotal("factory.events.duplicates"));
        assertEquals(200, get("/actuator/metrics/factory.events.registered").statusCode());
    }

    private double metricTotal(String name) {
        return metrics.find(name).counters().stream()
                .mapToDouble(io.micrometer.core.instrument.Counter::count).sum();
    }

    private String manualPayload(java.util.UUID requestId) {
        return """
                {"requestId":"%s","eventType":"QUALITY","description":"Evento manual",
                 "timestamp":"2026-05-20T10:00:00-03:00"}
                """.formatted(requestId);
    }

    private String payload(String id, String type) {
        return """
                {"eventId":"%s","eventType":"%s","description":"Evento de teste",
                 "timestamp":"2026-05-20T10:00:00-03:00"}
                """.formatted(id, type);
    }

    private HttpResponse<String> post(String body) throws Exception {
        return postAt("/api/events", body);
    }

    private HttpResponse<String> postAt(String path, String body) throws Exception {
        return client.send(HttpRequest.newBuilder(uri(path))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path) throws Exception {
        return get(uri(path));
    }

    private HttpResponse<String> get(URI uri) throws Exception {
        return client.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private long count(String table) {
        // Table names are constants supplied by these tests, never request input.
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }
}

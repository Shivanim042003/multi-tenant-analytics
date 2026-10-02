package com.analytics.platform.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.analytics.platform.chaos.ChaosHooks;
import com.analytics.platform.ingest.EventPublisher;
import com.analytics.platform.model.EventRequest;
import com.fasterxml.jackson.databind.JsonNode;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.utility.DockerImageName;

/**
 * Real Kafka + ClickHouse + Redis (Testcontainers), started once per JVM and shared by all integration tests,
 * which also share one Spring context. Tests reset state in @BeforeEach.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class IntegrationTestBase {

    protected static final String KEY_A = "key-a";
    protected static final String KEY_B = "key-b";

    static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.1"));
    static final ClickHouseContainer CLICKHOUSE = new ClickHouseContainer("clickhouse/clickhouse-server:25.8");
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);

    static {
        Startables.deepStart(List.of(KAFKA, CLICKHOUSE, REDIS)).join();
    }

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", CLICKHOUSE::getJdbcUrl);
        registry.add("spring.datasource.username", CLICKHOUSE::getUsername);
        registry.add("spring.datasource.password", CLICKHOUSE::getPassword);
        registry.add("analytics.clickhouse.http-url",
                () -> "http://" + CLICKHOUSE.getHost() + ":" + CLICKHOUSE.getMappedPort(8123));
        registry.add("analytics.clickhouse.username", CLICKHOUSE::getUsername);
        registry.add("analytics.clickhouse.password", CLICKHOUSE::getPassword);
        registry.add("analytics.clickhouse.database", CLICKHOUSE::getDatabaseName);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("analytics.security.api-keys.key-a", () -> "tenant-a");
        registry.add("analytics.security.api-keys.key-b", () -> "tenant-b");
        registry.add("analytics.security.dev-tenant-count", () -> "0");
        registry.add("analytics.kafka.retry-interval-ms", () -> "50");
        registry.add("analytics.cache.ttl-seconds", () -> "60");
    }

    @Autowired protected TestRestTemplate rest;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected StringRedisTemplate redis;
    @Autowired protected ChaosHooks chaos;
    @Autowired protected MeterRegistry meters;
    @Autowired protected EventPublisher publisher;

    @BeforeEach
    void resetState() {
        chaos.reset();
        jdbc.execute("TRUNCATE TABLE events");
        redis.execute((RedisCallback<Object>) c -> {
            c.serverCommands().flushAll();
            return null;
        });
    }

    // ---------- HTTP helpers ----------

    protected ResponseEntity<String> post(String path, String apiKey, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (apiKey != null) {
            headers.set("X-API-Key", apiKey);
        }
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    protected ResponseEntity<JsonNode> get(String path, String apiKey, Object... uriVars) {
        HttpHeaders headers = new HttpHeaders();
        if (apiKey != null) {
            headers.set("X-API-Key", apiKey);
        }
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class, uriVars);
    }

    protected static EventRequest event(String type, String userId, BigDecimal amount, Instant ts) {
        return new EventRequest(UUID.randomUUID(), type, ts, userId, "web", amount, null, 1);
    }

    // ---------- consumption / ClickHouse helpers ----------

    protected double consumedCount() {
        return meters.counter("analytics.events.consumed").count();
    }

    protected void awaitConsumed(double baseline, int delta, Duration timeout) {
        Awaitility.await().atMost(timeout).pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> assertThat(consumedCount() - baseline).isGreaterThanOrEqualTo(delta));
    }

    /** Physical rows, including not-yet-merged duplicates. */
    protected long rawCount() {
        return jdbc.queryForObject("SELECT count() FROM events", Long.class);
    }

    /** Logical rows: what every API query sees. */
    protected long logicalCount() {
        return jdbc.queryForObject("SELECT count() FROM events FINAL", Long.class);
    }

    /** Rows in the logical view that share a (tenant_id, event_id) with another row. Must always be 0. */
    protected long logicalDuplicates() {
        return jdbc.queryForObject(
                "SELECT count() - uniqExact(tenant_id, event_id) FROM events FINAL", Long.class);
    }
}

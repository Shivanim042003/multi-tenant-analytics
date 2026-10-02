package com.analytics.platform;

import static org.assertj.core.api.Assertions.assertThat;

import com.analytics.platform.model.EventRequest;
import com.analytics.platform.support.IntegrationTestBase;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class EventApiIntegrationTest extends IntegrationTestBase {

    private static Map<String, Object> body(Object tenantIdOrNull) {
        Map<String, Object> m = new java.util.HashMap<>();
        m.put("eventId", UUID.randomUUID().toString());
        m.put("eventType", "page_view");
        m.put("timestamp", Instant.now().toString());
        m.put("userId", "user-1");
        m.put("source", "web");
        if (tenantIdOrNull != null) {
            m.put("tenantId", tenantIdOrNull);
        }
        return m;
    }

    @Test
    void missingApiKeyIsRejected() {
        assertThat(post("/api/v1/events", null, body(null)).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void invalidApiKeyIsRejected() {
        assertThat(post("/api/v1/events", "wrong", body(null)).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void analyticsEndpointsRequireAuthentication() {
        assertThat(get("/api/v1/analytics/count?from={f}&to={t}", null,
                Instant.now().minusSeconds(60), Instant.now()).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void acceptedEventIsStoredUnderTheAuthenticatedTenantNotTheBodyTenant() {
        double baseline = consumedCount();

        // The client tries to write into tenant-b while authenticated as tenant-a.
        var response = post("/api/v1/events", KEY_A, body("tenant-b"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        awaitConsumed(baseline, 1, Duration.ofSeconds(60));

        List<String> tenants = jdbc.queryForList("SELECT DISTINCT tenant_id FROM events", String.class);
        assertThat(tenants).containsExactly("tenant-a");
    }

    @Test
    void invalidPayloadsAreRejectedWith400() {
        Map<String, Object> noType = body(null);
        noType.remove("eventType");
        assertThat(post("/api/v1/events", KEY_A, noType).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        Map<String, Object> badVersion = body(null);
        badVersion.put("schemaVersion", 0);
        assertThat(post("/api/v1/events", KEY_A, badVersion).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        Map<String, Object> unsupportedVersion = body(null);
        unsupportedVersion.put("schemaVersion", 2);
        assertThat(post("/api/v1/events", KEY_A, unsupportedVersion).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        Map<String, Object> tooPrecise = body(null);
        tooPrecise.put("amount", "1.23456");
        assertThat(post("/api/v1/events", KEY_A, tooPrecise).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        Map<String, Object> future = body(null);
        future.put("timestamp", Instant.now().plus(Duration.ofDays(3)).toString());
        assertThat(post("/api/v1/events", KEY_A, future).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        assertThat(post("/api/v1/events", KEY_A, "not json").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void batchEndpointAcceptsUpTo500AndRejectsMore() {
        double baseline = consumedCount();

        List<EventRequest> ok = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            ok.add(event("click", "user-" + i, null, Instant.now()));
        }
        assertThat(post("/api/v1/events/batch", KEY_A, ok).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        awaitConsumed(baseline, 50, Duration.ofSeconds(60));
        assertThat(logicalCount()).isEqualTo(50);

        List<EventRequest> tooMany = new ArrayList<>();
        for (int i = 0; i < 501; i++) {
            tooMany.add(event("click", "u", BigDecimal.ONE, Instant.now()));
        }
        assertThat(post("/api/v1/events/batch", KEY_A, tooMany).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(post("/api/v1/events/batch", KEY_A, List.of()).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}

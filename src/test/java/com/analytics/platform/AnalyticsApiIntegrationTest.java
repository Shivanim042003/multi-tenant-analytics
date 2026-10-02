package com.analytics.platform;

import static org.assertj.core.api.Assertions.assertThat;

import com.analytics.platform.model.EventRequest;
import com.analytics.platform.support.IntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class AnalyticsApiIntegrationTest extends IntegrationTestBase {

    private Instant base;
    private Instant from;
    private Instant to;

    @BeforeEach
    void seed() {
        base = Instant.now().truncatedTo(ChronoUnit.HOURS).minus(Duration.ofHours(4));
        from = base.minus(Duration.ofHours(2));
        to = base.plus(Duration.ofHours(5));

        double baseline = consumedCount();

        send("page_view", "user-1", null,
                base.plus(Duration.ofMinutes(5)));

        send("page_view", "user-1", null,
                base.plus(Duration.ofMinutes(15)));

        send("page_view", "user-2", null,
                base.plus(Duration.ofHours(1)).plus(Duration.ofMinutes(5)));

        send("purchase", "user-1", new BigDecimal("10.5000"),
                base.plus(Duration.ofHours(1)).plus(Duration.ofMinutes(10)));

        send("purchase", "user-3", new BigDecimal("20.2500"),
                base.plus(Duration.ofHours(2)).plus(Duration.ofMinutes(1)));

        send("click", "user-2", null,
                base.plus(Duration.ofHours(2)).plus(Duration.ofMinutes(2)));

        awaitConsumed(baseline, 6, Duration.ofSeconds(60));
    }

    private void send(String type, String user, BigDecimal amount, Instant ts) {
        post(
                "/api/v1/events",
                KEY_A,
                new EventRequest(
                        UUID.randomUUID(),
                        type,
                        ts,
                        user,
                        "web",
                        amount,
                        null,
                        1
                )
        );
    }

    private void sendAsTenant(
            String apiKey,
            String type,
            String user,
            BigDecimal amount,
            Instant ts
    ) {
        post(
                "/api/v1/events",
                apiKey,
                new EventRequest(
                        UUID.randomUUID(),
                        type,
                        ts,
                        user,
                        "web",
                        amount,
                        null,
                        1
                )
        );
    }

    private static final String RANGE = "from={f}&to={t}";

    @Test
    void countsAllAndByType() {
        assertThat(
                get("/api/v1/analytics/count?" + RANGE, KEY_A, from, to)
                        .getBody().get("count").asLong()
        ).isEqualTo(6);

        assertThat(
                get("/api/v1/analytics/count?" + RANGE + "&eventType=purchase",
                        KEY_A, from, to)
                        .getBody().get("count").asLong()
        ).isEqualTo(2);

        JsonNode items =
                get("/api/v1/analytics/by-type?" + RANGE, KEY_A, from, to)
                        .getBody().get("items");

        assertThat(items).hasSize(3);
        assertThat(items.get(0).get("eventType").asText())
                .isEqualTo("page_view");
        assertThat(items.get(0).get("count").asLong())
                .isEqualTo(3);
    }

    @Test
    void hourlyTimeSeries() {
        JsonNode body =
                get("/api/v1/analytics/timeseries?" + RANGE + "&bucket=hour",
                        KEY_A, from, to)
                        .getBody();

        assertThat(body.get("bucket").asText())
                .isEqualTo("hour");

        assertThat(body.get("points"))
                .hasSize(3);

        body.get("points").forEach(
                p -> assertThat(p.get("count").asLong())
                        .isEqualTo(2)
        );

        assertThat(
                Instant.parse(
                        body.get("points").get(0)
                                .get("timestamp").asText()
                )
        ).isEqualTo(base);
    }

    @Test
    void revenueAndTopUsers() {
        JsonNode revenue =
                get("/api/v1/analytics/revenue?" + RANGE,
                        KEY_A, from, to)
                        .getBody();

        assertThat(revenue.get("total").decimalValue())
                .isEqualByComparingTo("30.75");

        assertThat(revenue.get("eventsWithAmount").asLong())
                .isEqualTo(2);

        JsonNode top =
                get("/api/v1/analytics/top-users?" + RANGE + "&limit=2",
                        KEY_A, from, to)
                        .getBody().get("items");

        assertThat(top).hasSize(2);

        assertThat(top.get(0).get("userId").asText())
                .isEqualTo("user-1");

        assertThat(top.get(0).get("count").asLong())
                .isEqualTo(3);
    }

    @Test
    void repeatedQueryIsServedFromCache() {
        double hitsBefore =
                meters.counter("analytics.cache", "result", "hit").count();

        get("/api/v1/analytics/count?" + RANGE, KEY_A, from, to);
        get("/api/v1/analytics/count?" + RANGE, KEY_A, from, to);

        assertThat(
                meters.counter("analytics.cache", "result", "hit").count()
                        - hitsBefore
        ).isEqualTo(1.0);
    }

    @Test
    void cacheIsIsolatedBetweenTenants() {

        // Tenant B has its own event in the exact same query range.
        sendAsTenant(
                KEY_B,
                "page_view",
                "tenant-b-user",
                null,
                base.plus(Duration.ofMinutes(30))
        );

        // Tenant A populates its cache.
        JsonNode tenantAFirst =
                get("/api/v1/analytics/count?" + RANGE,
                        KEY_A, from, to)
                        .getBody();

        assertThat(tenantAFirst.get("count").asLong())
                .isEqualTo(6);

        // Tenant B must not receive Tenant A's cached value.
        JsonNode tenantBFirst =
                get("/api/v1/analytics/count?" + RANGE,
                        KEY_B, from, to)
                        .getBody();

        assertThat(tenantBFirst.get("count").asLong())
                .isEqualTo(1);

        // Tenant B's second request should hit B's own cache entry.
        double hitsBefore =
                meters.counter("analytics.cache", "result", "hit").count();

        JsonNode tenantBSecond =
                get("/api/v1/analytics/count?" + RANGE,
                        KEY_B, from, to)
                        .getBody();

        assertThat(tenantBSecond.get("count").asLong())
                .isEqualTo(1);

        assertThat(
                meters.counter("analytics.cache", "result", "hit").count()
                        - hitsBefore
        ).isEqualTo(1.0);
    }

    @Test
    void invalidParametersAreRejected() {
        assertThat(
                get("/api/v1/analytics/count?from={a}&to={b}",
                        KEY_A, to, from)
                        .getStatusCode()
        ).isEqualTo(HttpStatus.BAD_REQUEST);

        assertThat(
                get("/api/v1/analytics/count?from={f}&to={t}",
                        KEY_A,
                        to.minus(Duration.ofDays(200)),
                        to)
                        .getStatusCode()
        ).isEqualTo(HttpStatus.BAD_REQUEST);

        assertThat(
                get("/api/v1/analytics/top-users?" + RANGE + "&limit=1000",
                        KEY_A, from, to)
                        .getStatusCode()
        ).isEqualTo(HttpStatus.BAD_REQUEST);

        assertThat(
                get("/api/v1/analytics/timeseries?" + RANGE + "&bucket=week",
                        KEY_A, from, to)
                        .getStatusCode()
        ).isEqualTo(HttpStatus.BAD_REQUEST);

        assertThat(
                get("/api/v1/analytics/count", KEY_A)
                        .getStatusCode()
        ).isEqualTo(HttpStatus.BAD_REQUEST);

        assertThat(
                get("/api/v1/analytics/count?from=garbage&to=garbage",
                        KEY_A)
                        .getStatusCode()
        ).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}

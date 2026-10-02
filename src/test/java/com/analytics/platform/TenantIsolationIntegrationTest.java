package com.analytics.platform;

import static org.assertj.core.api.Assertions.assertThat;

import com.analytics.platform.support.IntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** Tenant A must never see tenant B's data, through the database or through the cache. */
class TenantIsolationIntegrationTest extends IntegrationTestBase {

    private static final String COUNT = "/api/v1/analytics/count?from={f}&to={t}";

    @Test
    void tenantsOnlySeeTheirOwnDataEverywhere() {
        double baseline = consumedCount();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);

        for (int i = 0; i < 3; i++) {
            post("/api/v1/events", KEY_A, event("purchase", "user-a", new BigDecimal("10.0000"), now));
        }
        for (int i = 0; i < 5; i++) {
            post("/api/v1/events", KEY_B, event("purchase", "user-b", new BigDecimal("1.0000"), now));
        }
        awaitConsumed(baseline, 8, Duration.ofSeconds(60));

        Instant from = now.minus(Duration.ofHours(1));
        Instant to = now.plus(Duration.ofHours(1));

        // Call A first so its result is cached, then B with identical parameters.
        assertThat(get(COUNT, KEY_A, from, to).getBody().get("count").asLong()).isEqualTo(3);
        assertThat(get(COUNT, KEY_B, from, to).getBody().get("count").asLong()).isEqualTo(5);

        // Second round is served from the cache; results must still be per-tenant.
        assertThat(get(COUNT, KEY_A, from, to).getBody().get("count").asLong()).isEqualTo(3);
        assertThat(get(COUNT, KEY_B, from, to).getBody().get("count").asLong()).isEqualTo(5);

        JsonNode topA = get("/api/v1/analytics/top-users?from={f}&to={t}", KEY_A, from, to).getBody();
        JsonNode topB = get("/api/v1/analytics/top-users?from={f}&to={t}", KEY_B, from, to).getBody();
        assertThat(topA.get("items")).hasSize(1);
        assertThat(topA.get("items").get(0).get("userId").asText()).isEqualTo("user-a");
        assertThat(topB.get("items").get(0).get("userId").asText()).isEqualTo("user-b");

        JsonNode revenueA = get("/api/v1/analytics/revenue?from={f}&to={t}", KEY_A, from, to).getBody();
        JsonNode revenueB = get("/api/v1/analytics/revenue?from={f}&to={t}", KEY_B, from, to).getBody();
        assertThat(revenueA.get("total").decimalValue()).isEqualByComparingTo("30");
        assertThat(revenueB.get("total").decimalValue()).isEqualByComparingTo("5");
    }

    @Test
    void cacheKeysAreAlwaysTenantPrefixed() {
        double baseline = consumedCount();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        post("/api/v1/events", KEY_A, event("click", "u", null, now));
        post("/api/v1/events", KEY_B, event("click", "u", null, now));
        awaitConsumed(baseline, 2, Duration.ofSeconds(60));

        Instant from = now.minus(Duration.ofHours(1));
        Instant to = now.plus(Duration.ofHours(1));
        get(COUNT, KEY_A, from, to);
        get(COUNT, KEY_B, from, to);

        Set<String> keys = redis.keys("*");
        assertThat(keys).isNotEmpty();
        assertThat(keys).allMatch(k -> k.startsWith("tenant:tenant-a:") || k.startsWith("tenant:tenant-b:"));
        assertThat(redis.keys("tenant:tenant-a:*")).hasSize(1);
        assertThat(redis.keys("tenant:tenant-b:*")).hasSize(1);
    }

    @Test
    void tenantCannotBeChosenThroughQueryParameters() {
        double baseline = consumedCount();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        post("/api/v1/events", KEY_B, event("click", "u", null, now));
        awaitConsumed(baseline, 1, Duration.ofSeconds(60));

        var response = get(COUNT + "&tenantId=tenant-b&tenant_id=tenant-b", KEY_A,
                now.minus(Duration.ofHours(1)), now.plus(Duration.ofHours(1)));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("count").asLong()).isZero();
    }
}

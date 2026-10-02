package com.analytics.platform;

import static org.assertj.core.api.Assertions.assertThat;

import com.analytics.platform.model.EventRequest;
import com.analytics.platform.support.IntegrationTestBase;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Definition used throughout: a "duplicate in query results" is a (tenant_id, event_id) pair that appears more
 * than once in the logical (FINAL) view, i.e. in anything an API query can return. Physical duplicates may exist
 * until ClickHouse merges parts; they are reported separately and are NOT a defect.
 */
class DeduplicationIntegrationTest extends IntegrationTestBase {

    @Test
    void sameEventDeliveredFiveTimesIsCountedOnce() {
        double baseline = consumedCount();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        EventRequest event = new EventRequest(UUID.randomUUID(), "purchase", now, "user-1", "web", null, null, 1);

        for (int i = 0; i < 5; i++) {
            post("/api/v1/events", KEY_A, event);
        }
        awaitConsumed(baseline, 5, Duration.ofSeconds(60));

        System.out.printf("DEDUP raw_rows=%d logical_rows=%d%n", rawCount(), logicalCount());

        assertThat(logicalCount()).isEqualTo(1);
        assertThat(logicalDuplicates()).isZero();

        var count = get("/api/v1/analytics/count?from={f}&to={t}", KEY_A,
                now.minus(Duration.ofHours(1)), now.plus(Duration.ofHours(1)));
        assertThat(count.getBody().get("count").asLong()).isEqualTo(1);
    }

    @Test
    void sameEventIdInDifferentTenantsAreDistinctEvents() {
        double baseline = consumedCount();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        EventRequest event = new EventRequest(UUID.randomUUID(), "click", now, "user-1", "web", null, null, 1);

        post("/api/v1/events", KEY_A, event);
        post("/api/v1/events", KEY_B, event);
        awaitConsumed(baseline, 2, Duration.ofSeconds(60));

        assertThat(logicalCount()).isEqualTo(2);
        assertThat(logicalDuplicates()).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count() FROM events FINAL WHERE tenant_id = 'tenant-a'", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count() FROM events FINAL WHERE tenant_id = 'tenant-b'", Long.class)).isEqualTo(1);
    }
}

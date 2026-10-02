package com.analytics.platform;

import static org.assertj.core.api.Assertions.assertThat;

import com.analytics.platform.chaos.ChaosHooks.Point;
import com.analytics.platform.model.AnalyticsEvent;
import com.analytics.platform.support.IntegrationTestBase;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * 10,000 unique events, 2,500 of them redelivered (same ids), with consumer failures injected before and after
 * the ClickHouse insert. Seeded, so the workload is reproducible.
 */
class ReplayReliabilityIntegrationTest extends IntegrationTestBase {

    private static final String[] TYPES = {"page_view", "click", "purchase", "signup"};

    @Test
    void replayWithRedeliveryAndFailuresLeavesExactlyTheUniqueEvents() {
        Random rnd = new Random(42);
        Instant base = Instant.now().minus(Duration.ofHours(2)).truncatedTo(ChronoUnit.MILLIS);

        List<AnalyticsEvent> unique = new ArrayList<>(10_000);
        for (int i = 0; i < 10_000; i++) {
            unique.add(new AnalyticsEvent(new UUID(rnd.nextLong(), rnd.nextLong()),
                    String.format("tenant-%02d", i % 10), TYPES[i % TYPES.length],
                    base.plusMillis(i * 37L), "user-" + (i % 500), "web", null, Map.of(), 1));
        }
        List<AnalyticsEvent> redelivered = IntStream.range(0, 2_500)
                .mapToObj(i -> unique.get(rnd.nextInt(unique.size()))).toList();

        double baseline = consumedCount();
        chaos.failNext(Point.BEFORE_INSERT, 2);
        chaos.failNext(Point.AFTER_INSERT, 3);

        publishInChunks(unique);
        publishInChunks(redelivered);

        awaitConsumed(baseline, 12_500, Duration.ofMinutes(3));

        long raw = rawCount();
        long logical = logicalCount();
        System.out.printf("REPLAY published=12500 unique=10000 raw_rows=%d logical_rows=%d logical_duplicates=%d%n",
                raw, logical, logicalDuplicates());

        assertThat(logical).isEqualTo(10_000);
        assertThat(logicalDuplicates()).isZero();
        assertThat(jdbc.queryForObject("SELECT uniqExact(tenant_id, event_id) FROM events FINAL", Long.class))
                .isEqualTo(10_000);
    }

    private void publishInChunks(List<AnalyticsEvent> events) {
        for (int i = 0; i < events.size(); i += 500) {
            publisher.publishAll(events.subList(i, Math.min(i + 500, events.size())));
        }
    }
}

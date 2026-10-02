package com.analytics.platform;

import static org.assertj.core.api.Assertions.assertThat;

import com.analytics.platform.chaos.ChaosHooks.Point;
import com.analytics.platform.model.EventRequest;
import com.analytics.platform.support.IntegrationTestBase;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Failures injected on both sides of the ClickHouse insert; no event may be lost or visible twice. */
class ConsumerFailureIntegrationTest extends IntegrationTestBase {

    @Test
    void failuresBeforeAndAfterInsertLoseNothingAndLeaveNoLogicalDuplicates() {
        double baseline = consumedCount();
        chaos.failNext(Point.BEFORE_INSERT, 1);
        chaos.failNext(Point.AFTER_INSERT, 2);   // insert succeeded, offset commit "crashed" -> redelivery

        Instant now = Instant.now();
        for (int b = 0; b < 2; b++) {
            List<EventRequest> batch = new ArrayList<>();
            for (int i = 0; i < 100; i++) {
                batch.add(event("click", "user-" + i, null, now.minusMillis(b * 1000L + i)));
            }
            post("/api/v1/events/batch", KEY_A, batch);
        }

        awaitConsumed(baseline, 200, Duration.ofSeconds(90));

        System.out.printf("CHAOS raw_rows=%d logical_rows=%d%n", rawCount(), logicalCount());

        assertThat(logicalCount()).isEqualTo(200);
        assertThat(logicalDuplicates()).isZero();
        assertThat(rawCount()).isGreaterThanOrEqualTo(200);
    }
}

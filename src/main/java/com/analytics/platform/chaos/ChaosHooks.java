package com.analytics.platform.chaos;

import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

/**
 * Failure injection points inside the consumer, used by the reliability tests.
 * Default state is "no failures", so it is inert unless a test arms it.
 *
 * BEFORE_INSERT: crash after reading a batch, before ClickHouse saw it.
 * AFTER_INSERT : crash after ClickHouse accepted the batch but BEFORE the Kafka offset commit
 *                (the classic duplicate-producing window of at-least-once delivery).
 */
@Component
public class ChaosHooks {

    public enum Point { BEFORE_INSERT, AFTER_INSERT }

    private final AtomicInteger beforeInsert = new AtomicInteger();
    private final AtomicInteger afterInsert = new AtomicInteger();

    public void failNext(Point point, int times) {
        counter(point).set(times);
    }

    public void maybeFail(Point point) {
        if (counter(point).getAndUpdate(v -> v > 0 ? v - 1 : 0) > 0) {
            throw new SimulatedFailureException("Simulated consumer failure at " + point);
        }
    }

    public void reset() {
        beforeInsert.set(0);
        afterInsert.set(0);
    }

    private AtomicInteger counter(Point point) {
        return point == Point.BEFORE_INSERT ? beforeInsert : afterInsert;
    }
}

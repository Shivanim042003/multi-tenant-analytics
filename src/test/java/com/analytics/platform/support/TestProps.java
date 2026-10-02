package com.analytics.platform.support;

import com.analytics.platform.config.AnalyticsProperties;
import java.util.Map;

public final class TestProps {

    private TestProps() {
    }

    public static AnalyticsProperties defaults() {
        return new AnalyticsProperties(
                new AnalyticsProperties.Security(Map.of(), 0, "dev-key-"),
                new AnalyticsProperties.Kafka("analytics-events", "analytics-events.dlq", 3, "g", 100, 1000),
                new AnalyticsProperties.ClickHouse("http://localhost:8123", "u", "p", "analytics", "optimized"),
                new AnalyticsProperties.Cache(true, 30),
                new AnalyticsProperties.Query(92, 100));
    }
}

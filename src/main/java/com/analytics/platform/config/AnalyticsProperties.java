package com.analytics.platform.config;

import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "analytics")
public record AnalyticsProperties(
        @DefaultValue Security security,
        @DefaultValue Kafka kafka,
        @DefaultValue ClickHouse clickhouse,
        @DefaultValue Cache cache,
        @DefaultValue Query query) {

    public record Security(
            Map<String, String> apiKeys,
            @DefaultValue("0") int devTenantCount,
            @DefaultValue("dev-key-") String devKeyPrefix) {
    }

    public record Kafka(
            @DefaultValue("analytics-events") String topic,
            @DefaultValue("analytics-events.dlq") String dlqTopic,
            @DefaultValue("3") int partitions,
            @DefaultValue("analytics-ingest") String consumerGroup,
            @DefaultValue("1000") long retryIntervalMs,
            @DefaultValue("10000") long publishTimeoutMs) {
    }

    public record ClickHouse(
            @DefaultValue("http://localhost:8123") String httpUrl,
            @DefaultValue("analytics") String username,
            @DefaultValue("analytics") String password,
            @DefaultValue("analytics") String database,
            @DefaultValue("optimized") String schemaVariant) {
    }

    public record Cache(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("30") long ttlSeconds) {
    }

    public record Query(
            @DefaultValue("92") int maxRangeDays,
            @DefaultValue("100") int maxTopN) {
    }
}

package com.analytics.platform.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Internal event (what goes on Kafka). tenantId is always server-derived. Dedup identity: (tenantId, eventId). */
public record AnalyticsEvent(

        UUID eventId,

        String tenantId,

        String eventType,

        Instant timestamp,

        String userId,

        String source,

        BigDecimal amount,

        Map<String, String> properties,

        int schemaVersion

) {

    public AnalyticsEvent {
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(tenantId, "tenantId must not be null");

        if (tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId must not be blank");
        }

        if (schemaVersion < 1) {
            throw new IllegalArgumentException("schemaVersion must be >= 1");
        }
    }
}

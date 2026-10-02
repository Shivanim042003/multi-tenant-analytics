package com.analytics.platform.ingest;

import com.analytics.platform.model.AnalyticsEvent;
import com.analytics.platform.model.EventRequest;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Builds the internal event from a validated request plus the authenticated tenant. */
@Component
public class EventMapper {

    public static final int CURRENT_SCHEMA_VERSION = 1;
    private static final Duration MAX_CLOCK_SKEW = Duration.ofHours(24);
    private static final int MAX_PROPERTY_LENGTH = 256;

    public AnalyticsEvent toEvent(EventRequest request, String tenantId) {
        int schemaVersion = request.schemaVersion() == null ? CURRENT_SCHEMA_VERSION : request.schemaVersion();
        if (schemaVersion != CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unsupported schemaVersion: " + schemaVersion);
        }

        // DateTime64(3): truncate to millis so the dedup key is identical on every redelivery.
        Instant timestamp = request.timestamp().truncatedTo(ChronoUnit.MILLIS);
        if (timestamp.isAfter(Instant.now().plus(MAX_CLOCK_SKEW))) {
            throw new IllegalArgumentException("timestamp is too far in the future");
        }

        Map<String, String> properties = request.properties() == null ? Map.of() : request.properties();
        properties.forEach((k, v) -> {
            if (k == null || v == null || k.length() > MAX_PROPERTY_LENGTH || v.length() > MAX_PROPERTY_LENGTH) {
                throw new IllegalArgumentException("properties must be non-null strings of at most "
                        + MAX_PROPERTY_LENGTH + " characters");
            }
        });

        return new AnalyticsEvent(
                request.eventId(),
                tenantId,
                request.eventType(),
                timestamp,
                request.userId(),
                request.source(),
                request.amount(),
                properties,
                schemaVersion);
    }
}

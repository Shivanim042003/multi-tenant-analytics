package com.analytics.platform.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class AnalyticsEventTest {

    @Test
    void createsValidAnalyticsEvent() {

        UUID eventId = UUID.randomUUID();

        AnalyticsEvent event = new AnalyticsEvent(
                eventId,
                "tenant-001",
                "purchase",
                Instant.parse("2026-10-01T23:00:00Z"),
                "user-123",
                "web",
                null,
                Map.of("product", "laptop"),
                1
        );

        assertEquals(eventId, event.eventId());
        assertEquals("tenant-001", event.tenantId());
        assertEquals("purchase", event.eventType());
        assertEquals(1, event.schemaVersion());
    }

    @Test
    void rejectsNullEventId() {

        NullPointerException exception = assertThrows(
                NullPointerException.class,
                () -> new AnalyticsEvent(
                        null,
                        "tenant-001",
                        "purchase",
                        Instant.now(),
                        "user-123",
                        "web",
                        null,
                        Map.of(),
                        1
                )
        );

        assertEquals("eventId must not be null", exception.getMessage());
    }

    @Test
    void rejectsNullTenantId() {

        NullPointerException exception = assertThrows(
                NullPointerException.class,
                () -> new AnalyticsEvent(
                        UUID.randomUUID(),
                        null,
                        "purchase",
                        Instant.now(),
                        "user-123",
                        "web",
                        null,
                        Map.of(),
                        1
                )
        );

        assertEquals("tenantId must not be null", exception.getMessage());
    }

    @Test
    void rejectsBlankTenantId() {

        assertThrows(
                IllegalArgumentException.class,
                () -> new AnalyticsEvent(
                        UUID.randomUUID(),
                        " ",
                        "purchase",
                        Instant.now(),
                        "user-123",
                        "web",
                        null,
                        Map.of(),
                        1
                )
        );
    }

    @Test
    void rejectsInvalidSchemaVersion() {

        assertThrows(
                IllegalArgumentException.class,
                () -> new AnalyticsEvent(
                        UUID.randomUUID(),
                        "tenant-001",
                        "purchase",
                        Instant.now(),
                        "user-123",
                        "web",
                        null,
                        Map.of(),
                        0
                )
        );
    }
}

package com.analytics.platform.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class EventRequestTest {

    @Test
    void createsValidEventRequest() {

        EventRequest request = new EventRequest(
                UUID.randomUUID(),
                "purchase",
                Instant.parse("2026-10-01T23:00:00Z"),
                "user-123",
                "web",
                null,
                Map.of("product", "laptop"),
                1
        );

        assertNotNull(request.eventId());
        assertEquals("purchase", request.eventType());
        assertEquals("web", request.source());
        assertEquals(1, request.schemaVersion());
    }

    @Test
    void schemaVersionCanBeNullBeforeApiValidation() {

        EventRequest request = new EventRequest(
                UUID.randomUUID(),
                "page_view",
                Instant.now(),
                "user-123",
                "web",
                null,
                Map.of(),
                null
        );

        assertNull(request.schemaVersion());
    }
}
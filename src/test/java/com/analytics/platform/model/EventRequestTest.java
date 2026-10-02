package com.analytics.platform.model;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class EventRequestTest {

    private static ValidatorFactory validatorFactory;
    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    @AfterAll
    static void closeValidatorFactory() {
        validatorFactory.close();
    }

    private static boolean hasViolationOn(Set<ConstraintViolation<EventRequest>> violations, String field) {
        return violations.stream().anyMatch(v -> v.getPropertyPath().toString().equals(field));
    }

    private static EventRequest valid() {
        return new EventRequest(UUID.randomUUID(), "purchase", Instant.now(), "user-123", "web", null, Map.of(), 1);
    }

    @Test
    void createsValidEventRequest() {
        EventRequest request = new EventRequest(
                UUID.randomUUID(), "purchase", Instant.parse("2026-10-01T23:00:00Z"),
                "user-123", "web", null, Map.of("product", "laptop"), 1);

        assertNotNull(request.eventId());
        assertEquals("purchase", request.eventType());
        assertEquals("web", request.source());
        assertEquals(1, request.schemaVersion());
    }

    @Test
    void validRequestHasNoViolations() {
        assertTrue(validator.validate(valid()).isEmpty());
    }

    @Test
    void schemaVersionCanBeNullBeforeApiValidation() {
        EventRequest request = new EventRequest(
                UUID.randomUUID(), "page_view", Instant.now(), "user-123", "web", null, Map.of(), null);

        assertNull(request.schemaVersion());
    }

    @Test
    void rejectsNullEventId() {
        EventRequest request = new EventRequest(
                null, "purchase", Instant.now(), "user-123", "web", null, Map.of(), 1);

        assertTrue(hasViolationOn(validator.validate(request), "eventId"));
    }

    @Test
    void rejectsBlankEventType() {
        EventRequest request = new EventRequest(
                UUID.randomUUID(), "   ", Instant.now(), "user-123", "web", null, Map.of(), 1);

        assertTrue(hasViolationOn(validator.validate(request), "eventType"));
    }

    @Test
    void rejectsNullTimestamp() {
        EventRequest request = new EventRequest(
                UUID.randomUUID(), "purchase", null, "user-123", "web", null, Map.of(), 1);

        assertTrue(hasViolationOn(validator.validate(request), "timestamp"));
    }

    @Test
    void rejectsBlankSource() {
        EventRequest request = new EventRequest(
                UUID.randomUUID(), "purchase", Instant.now(), "user-123", "   ", null, Map.of(), 1);

        assertTrue(hasViolationOn(validator.validate(request), "source"));
    }

    @Test
    void rejectsSchemaVersionBelowOne() {
        EventRequest request = new EventRequest(
                UUID.randomUUID(), "purchase", Instant.now(), "user-123", "web", null, Map.of(), 0);

        assertTrue(hasViolationOn(validator.validate(request), "schemaVersion"));
    }

    @Test
    void rejectsAmountWithTooManyDecimals() {
        EventRequest request = new EventRequest(
                UUID.randomUUID(), "purchase", Instant.now(), "user-123", "web",
                new BigDecimal("1.23456"), Map.of(), 1);

        assertTrue(hasViolationOn(validator.validate(request), "amount"));
    }

    @Test
    void rejectsTooManyProperties() {
        Map<String, String> props = new HashMap<>();
        for (int i = 0; i < 51; i++) {
            props.put("k" + i, "v");
        }
        EventRequest request = new EventRequest(
                UUID.randomUUID(), "purchase", Instant.now(), "user-123", "web", null, props, 1);

        assertTrue(hasViolationOn(validator.validate(request), "properties"));
    }
}

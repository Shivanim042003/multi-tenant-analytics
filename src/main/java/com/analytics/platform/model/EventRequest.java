package com.analytics.platform.model;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record EventRequest(

        @NotNull
        UUID eventId,

        @NotBlank
        String eventType,

        @NotNull
        Instant timestamp,

        String userId,

        @NotBlank
        String source,

        BigDecimal amount,

        Map<String, String> properties,

        @Min(1)
        Integer schemaVersion

) {
}
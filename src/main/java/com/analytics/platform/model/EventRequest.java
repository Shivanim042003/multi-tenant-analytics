package com.analytics.platform.model;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Client-facing event. Deliberately has NO tenantId: the tenant comes from the API key. */
public record EventRequest(

        @NotNull
        UUID eventId,

        @NotBlank
        @Size(max = 100)
        String eventType,

        @NotNull
        Instant timestamp,

        @Size(max = 200)
        String userId,

        @NotBlank
        @Size(max = 50)
        String source,

        @Digits(integer = 14, fraction = 4)
        BigDecimal amount,

        @Size(max = 50)
        Map<String, String> properties,

        @Min(1)
        Integer schemaVersion

) {
}

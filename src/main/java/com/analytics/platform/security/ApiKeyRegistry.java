package com.analytics.platform.security;

import com.analytics.platform.config.AnalyticsProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Maps API keys to tenants. Only SHA-256 hashes of keys are kept in memory.
 * Tenant ids are restricted to [A-Za-z0-9_-] so they are always safe inside cache keys and SQL parameters.
 */
@Component
public class ApiKeyRegistry {

    private static final Pattern TENANT_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    private final Map<String, String> tenantByKeyHash = new HashMap<>();

    @Autowired
    public ApiKeyRegistry(AnalyticsProperties props) {
        this(props.security().apiKeys(), props.security().devTenantCount(), props.security().devKeyPrefix());
    }

    public ApiKeyRegistry(Map<String, String> apiKeys, int devTenantCount, String devKeyPrefix) {
        if (apiKeys != null) {
            apiKeys.forEach(this::register);
        }
        for (int i = 1; i <= devTenantCount; i++) {
            String tenant = String.format("tenant-%03d", i);
            register(devKeyPrefix + tenant, tenant);
        }
    }

    private void register(String apiKey, String tenantId) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("API key must not be blank");
        }
        if (tenantId == null || !TENANT_ID.matcher(tenantId).matches()) {
            throw new IllegalArgumentException("Invalid tenant id: " + tenantId);
        }
        tenantByKeyHash.put(sha256(apiKey), tenantId);
    }

    public Optional<String> tenantFor(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(tenantByKeyHash.get(sha256(apiKey)));
    }

    static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

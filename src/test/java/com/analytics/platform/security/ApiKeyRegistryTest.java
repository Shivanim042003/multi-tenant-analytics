package com.analytics.platform.security;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import org.junit.jupiter.api.Test;

class ApiKeyRegistryTest {

    @Test
    void resolvesConfiguredKeyToItsTenant() {
        ApiKeyRegistry registry = new ApiKeyRegistry(Map.of("secret-a", "tenant-a"), 0, "dev-key-");

        assertEquals("tenant-a", registry.tenantFor("secret-a").orElseThrow());
    }

    @Test
    void unknownBlankAndNullKeysResolveToNothing() {
        ApiKeyRegistry registry = new ApiKeyRegistry(Map.of("secret-a", "tenant-a"), 0, "dev-key-");

        assertTrue(registry.tenantFor("nope").isEmpty());
        assertTrue(registry.tenantFor("  ").isEmpty());
        assertTrue(registry.tenantFor(null).isEmpty());
    }

    @Test
    void generatesDevTenants() {
        ApiKeyRegistry registry = new ApiKeyRegistry(Map.of(), 50, "dev-key-");

        assertEquals("tenant-001", registry.tenantFor("dev-key-tenant-001").orElseThrow());
        assertEquals("tenant-050", registry.tenantFor("dev-key-tenant-050").orElseThrow());
        assertTrue(registry.tenantFor("dev-key-tenant-051").isEmpty());
    }

    @Test
    void rejectsTenantIdsThatCouldBreakCacheKeys() {
        assertThrows(IllegalArgumentException.class,
                () -> new ApiKeyRegistry(Map.of("k", "tenant:evil"), 0, "dev-key-"));
    }
}

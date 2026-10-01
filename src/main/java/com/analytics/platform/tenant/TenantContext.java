package com.analytics.platform.tenant;

public final class TenantContext {

    private static final ThreadLocal<String> CURRENT_TENANT = new ThreadLocal<>();

    private TenantContext() {
    }

    public static void setTenantId(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId must not be null or blank");
        }

        CURRENT_TENANT.set(tenantId);
    }

    public static String getTenantId() {
        String tenantId = CURRENT_TENANT.get();

        if (tenantId == null) {
            throw new IllegalStateException("No tenant context is set");
        }

        return tenantId;
    }

    public static void clear() {
        CURRENT_TENANT.remove();
    }
}
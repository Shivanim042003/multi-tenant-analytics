package com.analytics.platform.tenant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TenantContextTest {

    @AfterEach
    void cleanUp() {
        TenantContext.clear();
    }

    @Test
    void setTenantIdThenGetTenantIdReturnsValue() {

        TenantContext.setTenantId("tenant-001");

        assertEquals("tenant-001", TenantContext.getTenantId());
    }

    @Test
    void getTenantIdWithoutContextThrowsException() {

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                TenantContext::getTenantId
        );

        assertEquals("No tenant context is set", exception.getMessage());
    }

    @Test
    void rejectsNullTenantId() {

        assertThrows(
                IllegalArgumentException.class,
                () -> TenantContext.setTenantId(null)
        );
    }

    @Test
    void rejectsBlankTenantId() {

        assertThrows(
                IllegalArgumentException.class,
                () -> TenantContext.setTenantId("   ")
        );
    }

    @Test
    void clearRemovesTenantContext() {

        TenantContext.setTenantId("tenant-001");

        TenantContext.clear();

        assertThrows(
                IllegalStateException.class,
                TenantContext::getTenantId
        );
    }

    @Test
    void tenantContextIsNotVisibleAcrossThreads() throws Exception {

        TenantContext.setTenantId("tenant-001");

        Thread otherThread = new Thread(() -> {
            assertThrows(
                    IllegalStateException.class,
                    TenantContext::getTenantId
            );
        });

        otherThread.start();
        otherThread.join();

        assertEquals("tenant-001", TenantContext.getTenantId());
    }
}
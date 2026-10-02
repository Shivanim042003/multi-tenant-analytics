package com.analytics.platform.clickhouse;

import com.analytics.platform.config.AnalyticsProperties;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

/**
 * Creates the events table at startup (idempotent) so a clean environment needs no manual SQL.
 * schema-variant selects the DDL: "optimized" (default) or "baseline" (deliberately poor, for the
 * query-optimization before/after experiment).
 */
@Component
public class ClickHouseSchemaInitializer implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(ClickHouseSchemaInitializer.class);

    private final ClickHouseHttpClient http;
    private final String variant;

    public ClickHouseSchemaInitializer(ClickHouseHttpClient http, AnalyticsProperties props) {
        this.http = http;
        this.variant = props.clickhouse().schemaVariant();
    }

    @Override
    public void afterPropertiesSet() {
        if (!variant.equals("optimized") && !variant.equals("baseline")) {
            throw new IllegalArgumentException("analytics.clickhouse.schema-variant must be optimized or baseline");
        }
        String ddl = loadDdl("clickhouse/schema-" + variant + ".sql");

        RuntimeException last = null;
        for (int attempt = 1; attempt <= 60; attempt++) {
            try {
                if (!http.database().equals("default")) {
                    http.execute("CREATE DATABASE IF NOT EXISTS " + http.database(), false);
                }
                http.execute(ddl, true);
                log.info("ClickHouse schema ready (variant={}, database={})", variant, http.database());
                return;
            } catch (RuntimeException e) {
                last = e;
                log.warn("ClickHouse not ready (attempt {}/60): {}", attempt, e.getMessage());
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        throw new IllegalStateException("Could not initialise ClickHouse schema", last);
    }

    private static String loadDdl(String resource) {
        try (InputStream in = ClickHouseSchemaInitializer.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Missing resource " + resource);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}

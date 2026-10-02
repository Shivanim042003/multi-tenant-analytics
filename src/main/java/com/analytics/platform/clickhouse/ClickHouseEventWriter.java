package com.analytics.platform.clickhouse;

import com.analytics.platform.model.AnalyticsEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.RoundingMode;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Component;

/** Batch insert of events. Idempotent by design: re-inserting the same event is absorbed by ReplacingMergeTree. */
@Component
@DependsOn("clickHouseSchemaInitializer")
public class ClickHouseEventWriter {

    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneOffset.UTC);

    private final ClickHouseHttpClient http;
    private final ObjectMapper mapper;

    public ClickHouseEventWriter(ClickHouseHttpClient http, ObjectMapper mapper) {
        this.http = http;
        this.mapper = mapper;
    }

    public void insert(List<AnalyticsEvent> events) {
        if (events.isEmpty()) {
            return;
        }
        StringBuilder body = new StringBuilder(events.size() * 256);
        try {
            for (AnalyticsEvent e : events) {
                ObjectNode row = mapper.createObjectNode();
                row.put("tenant_id", e.tenantId());
                row.put("event_id", e.eventId().toString());
                row.put("event_type", e.eventType());
                row.put("event_time", TS.format(e.timestamp()));
                row.put("user_id", e.userId() == null ? "" : e.userId());
                row.put("source", e.source());
                if (e.amount() == null) {
                    row.putNull("amount");
                } else {
                    row.put("amount", e.amount().setScale(4, RoundingMode.HALF_UP));
                }
                row.put("properties", mapper.writeValueAsString(e.properties() == null ? java.util.Map.of() : e.properties()));
                row.put("schema_version", e.schemaVersion());
                body.append(mapper.writeValueAsString(row)).append('\n');
            }
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Could not serialise events", ex);
        }
        http.insertJsonEachRow("events", body.toString());
    }
}

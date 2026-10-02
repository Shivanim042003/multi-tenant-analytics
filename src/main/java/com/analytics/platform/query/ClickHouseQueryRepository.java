package com.analytics.platform.query;

import com.analytics.platform.query.QueryResults.CountResult;
import com.analytics.platform.query.QueryResults.EventTypeCount;
import com.analytics.platform.query.QueryResults.EventTypeCounts;
import com.analytics.platform.query.QueryResults.Revenue;
import com.analytics.platform.query.QueryResults.TimePoint;
import com.analytics.platform.query.QueryResults.TimeSeries;
import com.analytics.platform.query.QueryResults.TopUsers;
import com.analytics.platform.query.QueryResults.UserCount;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Every query starts with "tenant_id = ?" and the tenant is a bound parameter supplied by the service layer
 * (from TenantContext). FINAL makes results deduplicated on (tenant_id, event_id) regardless of merge state.
 */
@Repository
@DependsOn("clickHouseSchemaInitializer")
public class ClickHouseQueryRepository {

    private static final String RANGE =
            " WHERE tenant_id = ? AND event_time >= fromUnixTimestamp64Milli(toInt64(?), 'UTC')"
                    + " AND event_time < fromUnixTimestamp64Milli(toInt64(?), 'UTC')";

    private final JdbcTemplate jdbc;
    private final MeterRegistry registry;

    public ClickHouseQueryRepository(JdbcTemplate jdbc, MeterRegistry registry) {
        this.jdbc = jdbc;
        this.registry = registry;
    }

    public CountResult count(String tenantId, long fromMs, long toMs, String eventType) {
        return timed("count", () -> {
            if (eventType == null) {
                return new CountResult(jdbc.queryForObject(
                        "SELECT count() FROM events FINAL" + RANGE, Long.class, tenantId, fromMs, toMs));
            }
            return new CountResult(jdbc.queryForObject(
                    "SELECT count() FROM events FINAL" + RANGE + " AND event_type = ?",
                    Long.class, tenantId, fromMs, toMs, eventType));
        });
    }

    public EventTypeCounts byType(String tenantId, long fromMs, long toMs) {
        return timed("by-type", () -> new EventTypeCounts(jdbc.query(
                "SELECT event_type, count() AS c FROM events FINAL" + RANGE
                        + " GROUP BY event_type ORDER BY c DESC, event_type ASC",
                (rs, i) -> new EventTypeCount(rs.getString(1), rs.getLong(2)),
                tenantId, fromMs, toMs)));
    }

    public TimeSeries timeSeries(String tenantId, long fromMs, long toMs, Bucket bucket, String eventType) {
        return timed("timeseries", () -> {
            String sql = "SELECT toUnixTimestamp(" + bucket.sqlFunction() + "(event_time)) * 1000 AS bucket, count() AS c"
                    + " FROM events FINAL" + RANGE
                    + (eventType == null ? "" : " AND event_type = ?")
                    + " GROUP BY bucket ORDER BY bucket";
            Object[] args = eventType == null
                    ? new Object[]{tenantId, fromMs, toMs}
                    : new Object[]{tenantId, fromMs, toMs, eventType};
            List<TimePoint> points = jdbc.query(sql,
                    (rs, i) -> new TimePoint(Instant.ofEpochMilli(rs.getLong(1)), rs.getLong(2)), args);
            return new TimeSeries(bucket.name().toLowerCase(), new ArrayList<>(points));
        });
    }

    public Revenue revenue(String tenantId, long fromMs, long toMs) {
        return timed("revenue", () -> jdbc.queryForObject(
                "SELECT coalesce(sum(amount), toDecimal64(0, 4)), count(amount) FROM events FINAL" + RANGE,
                (rs, i) -> {
                    BigDecimal total = rs.getBigDecimal(1);
                    return new Revenue(total == null ? BigDecimal.ZERO : total, rs.getLong(2));
                },
                tenantId, fromMs, toMs));
    }

    public TopUsers topUsers(String tenantId, long fromMs, long toMs, int limit) {
        return timed("top-users", () -> new TopUsers(jdbc.query(
                "SELECT user_id, count() AS c FROM events FINAL" + RANGE
                        + " AND user_id != '' GROUP BY user_id ORDER BY c DESC, user_id ASC LIMIT ?",
                (rs, i) -> new UserCount(rs.getString(1), rs.getLong(2)),
                tenantId, fromMs, toMs, limit)));
    }

    private <T> T timed(String name, Supplier<T> query) {
        return registry.timer("analytics.clickhouse.query", "query", name).record(query);
    }
}

package com.analytics.platform.query;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Response types. Also the JSON stored in Redis, so they must stay simple records. */
public final class QueryResults {

    private QueryResults() {
    }

    public record CountResult(long count) {
    }

    public record EventTypeCount(String eventType, long count) {
    }

    public record EventTypeCounts(List<EventTypeCount> items) {
    }

    public record TimePoint(Instant timestamp, long count) {
    }

    public record TimeSeries(String bucket, List<TimePoint> points) {
    }

    public record Revenue(BigDecimal total, long eventsWithAmount) {
    }

    public record UserCount(String userId, long count) {
    }

    public record TopUsers(List<UserCount> items) {
    }
}

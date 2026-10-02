package com.analytics.platform.api;

import com.analytics.platform.query.AnalyticsService;
import com.analytics.platform.query.QueryResults.CountResult;
import com.analytics.platform.query.QueryResults.EventTypeCounts;
import com.analytics.platform.query.QueryResults.Revenue;
import com.analytics.platform.query.QueryResults.TimeSeries;
import com.analytics.platform.query.QueryResults.TopUsers;
import java.time.Instant;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** All endpoints are scoped to the authenticated tenant; there is no tenant parameter. */
@RestController
@RequestMapping("/api/v1/analytics")
public class AnalyticsController {

    private final AnalyticsService service;

    public AnalyticsController(AnalyticsService service) {
        this.service = service;
    }

    @GetMapping("/count")
    public CountResult count(@RequestParam Instant from, @RequestParam Instant to,
                             @RequestParam(required = false) String eventType) {
        return service.count(from, to, eventType);
    }

    @GetMapping("/by-type")
    public EventTypeCounts byType(@RequestParam Instant from, @RequestParam Instant to) {
        return service.byType(from, to);
    }

    @GetMapping("/timeseries")
    public TimeSeries timeSeries(@RequestParam Instant from, @RequestParam Instant to,
                                 @RequestParam(defaultValue = "hour") String bucket,
                                 @RequestParam(required = false) String eventType) {
        return service.timeSeries(from, to, bucket, eventType);
    }

    @GetMapping("/revenue")
    public Revenue revenue(@RequestParam Instant from, @RequestParam Instant to) {
        return service.revenue(from, to);
    }

    @GetMapping("/top-users")
    public TopUsers topUsers(@RequestParam Instant from, @RequestParam Instant to,
                             @RequestParam(defaultValue = "10") int limit) {
        return service.topUsers(from, to, limit);
    }
}

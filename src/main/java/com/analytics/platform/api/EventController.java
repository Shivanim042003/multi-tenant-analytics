package com.analytics.platform.api;

import com.analytics.platform.ingest.EventMapper;
import com.analytics.platform.ingest.EventPublisher;
import com.analytics.platform.model.AnalyticsEvent;
import com.analytics.platform.model.EventRequest;
import com.analytics.platform.tenant.TenantContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/events")
@Validated
public class EventController {

    private final EventMapper mapper;
    private final EventPublisher publisher;

    public EventController(EventMapper mapper, EventPublisher publisher) {
        this.mapper = mapper;
        this.publisher = publisher;
    }

    /** 202 means: durably accepted by Kafka. It does NOT mean the event is queryable yet. */
    @PostMapping
    public ResponseEntity<IngestResponse> ingest(@Valid @RequestBody EventRequest request) {
        String tenantId = TenantContext.getTenantId();
        publisher.publish(mapper.toEvent(request, tenantId));
        return ResponseEntity.accepted().body(new IngestResponse(1));
    }

    @PostMapping("/batch")
    public ResponseEntity<IngestResponse> ingestBatch(
            @RequestBody @Size(min = 1, max = 500) List<@Valid EventRequest> requests) {
        String tenantId = TenantContext.getTenantId();
        List<AnalyticsEvent> events = requests.stream().map(r -> mapper.toEvent(r, tenantId)).toList();
        publisher.publishAll(events);
        return ResponseEntity.accepted().body(new IngestResponse(events.size()));
    }
}

package com.analytics.platform.ingest;

import com.analytics.platform.config.AnalyticsProperties;
import com.analytics.platform.model.AnalyticsEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Publishes events to Kafka, keyed by tenantId (per-tenant ordering, even spread across partitions).
 * Returns only after the broker acknowledged every record (acks=all + idempotent producer).
 */
@Component
public class EventPublisher {

    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper mapper;
    private final String topic;
    private final long timeoutMs;
    private final Counter published;
    private final Counter failed;

    public EventPublisher(KafkaTemplate<String, String> kafka, ObjectMapper mapper,
                          AnalyticsProperties props, MeterRegistry registry) {
        this.kafka = kafka;
        this.mapper = mapper;
        this.topic = props.kafka().topic();
        this.timeoutMs = props.kafka().publishTimeoutMs();
        this.published = Counter.builder("analytics.events.published").register(registry);
        this.failed = Counter.builder("analytics.events.publish.failed").register(registry);
    }

    public void publish(AnalyticsEvent event) {
        publishAll(List.of(event));
    }

    public void publishAll(List<AnalyticsEvent> events) {
        try {
            List<CompletableFuture<?>> futures = new ArrayList<>(events.size());
            for (AnalyticsEvent event : events) {
                futures.add(kafka.send(topic, event.tenantId(), mapper.writeValueAsString(event)));
            }
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get(timeoutMs, TimeUnit.MILLISECONDS);
            published.increment(events.size());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            failed.increment(events.size());
            throw new EventPublishException("Interrupted while publishing events", e);
        } catch (Exception e) {
            failed.increment(events.size());
            throw new EventPublishException("Could not publish events to Kafka", e);
        }
    }
}

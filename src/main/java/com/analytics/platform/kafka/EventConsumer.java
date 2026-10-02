package com.analytics.platform.kafka;

import com.analytics.platform.chaos.ChaosHooks;
import com.analytics.platform.chaos.ChaosHooks.Point;
import com.analytics.platform.clickhouse.ClickHouseEventWriter;
import com.analytics.platform.config.AnalyticsProperties;
import com.analytics.platform.model.AnalyticsEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * Kafka -> ClickHouse, at-least-once.
 *
 *   parse batch  ->  insert valid events  ->  DLQ poison records  ->  commit offsets (ack)
 *
 * The offset is committed only after ClickHouse accepted the batch. A crash/failure anywhere before the ack
 * means the whole batch is redelivered; the (tenant_id, event_id) deduplication in ClickHouse absorbs that.
 */
@Component
public class EventConsumer {

    private static final Logger log = LoggerFactory.getLogger(EventConsumer.class);

    private final ObjectMapper mapper;
    private final ClickHouseEventWriter writer;
    private final KafkaTemplate<String, String> kafka;
    private final ChaosHooks chaos;
    private final String dlqTopic;
    private final long timeoutMs;
    private final Counter consumed;
    private final Counter invalid;
    private final Counter failedBatches;
    private final Timer insertTimer;

    public EventConsumer(ObjectMapper mapper, ClickHouseEventWriter writer, KafkaTemplate<String, String> kafka,
                         ChaosHooks chaos, AnalyticsProperties props, MeterRegistry registry) {
        this.mapper = mapper;
        this.writer = writer;
        this.kafka = kafka;
        this.chaos = chaos;
        this.dlqTopic = props.kafka().dlqTopic();
        this.timeoutMs = props.kafka().publishTimeoutMs();
        this.consumed = Counter.builder("analytics.events.consumed").register(registry);
        this.invalid = Counter.builder("analytics.events.invalid").register(registry);
        this.failedBatches = Counter.builder("analytics.consumer.batch.failures").register(registry);
        this.insertTimer = Timer.builder("analytics.clickhouse.insert").register(registry);
    }

    @KafkaListener(
            topics = "${analytics.kafka.topic:analytics-events}",
            groupId = "${analytics.kafka.consumer-group:analytics-ingest}")
    public void onBatch(List<ConsumerRecord<String, String>> records, Acknowledgment ack) {
        try {
            List<AnalyticsEvent> valid = new ArrayList<>(records.size());
            List<ConsumerRecord<String, String>> poison = new ArrayList<>();
            List<String> reasons = new ArrayList<>();

            for (ConsumerRecord<String, String> record : records) {
                try {
                    AnalyticsEvent event = mapper.readValue(record.value(), AnalyticsEvent.class);
                    // Defense in depth: the message key is the tenant the API authenticated.
                    if (!event.tenantId().equals(record.key())) {
                        throw new IllegalArgumentException("record key does not match tenantId");
                    }
                    valid.add(event);
                } catch (Exception e) {
                    poison.add(record);
                    reasons.add(String.valueOf(e));
                }
            }

            chaos.maybeFail(Point.BEFORE_INSERT);

            if (!valid.isEmpty()) {
                insertTimer.record(() -> writer.insert(valid));
            }

            for (int i = 0; i < poison.size(); i++) {
                sendToDeadLetter(poison.get(i), reasons.get(i));
            }

            chaos.maybeFail(Point.AFTER_INSERT);

            ack.acknowledge();
            consumed.increment(valid.size());
            invalid.increment(poison.size());
        } catch (RuntimeException e) {
            failedBatches.increment();
            log.warn("Batch of {} records failed; it will be redelivered: {}", records.size(), e.toString());
            throw e;
        }
    }

    private void sendToDeadLetter(ConsumerRecord<String, String> record, String reason) {
        ProducerRecord<String, String> dead = new ProducerRecord<>(dlqTopic, null, record.key(), record.value());
        dead.headers().add("x-error", reason.getBytes(StandardCharsets.UTF_8));
        dead.headers().add("x-source", (record.topic() + "-" + record.partition() + "@" + record.offset())
                .getBytes(StandardCharsets.UTF_8));
        try {
            kafka.send(dead).get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while writing to DLQ", e);
        } catch (Exception e) {
            throw new IllegalStateException("Could not write to DLQ", e);
        }
    }
}

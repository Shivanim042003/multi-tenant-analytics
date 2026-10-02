package com.analytics.platform.kafka;

import com.analytics.platform.config.AnalyticsProperties;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
public class KafkaConfig {

    /** Topics are created by the app so a clean environment needs no manual steps. replicas=1: single-broker dev setup. */
    @Bean
    NewTopic eventsTopic(AnalyticsProperties props) {
        return TopicBuilder.name(props.kafka().topic()).partitions(props.kafka().partitions()).replicas(1).build();
    }

    @Bean
    NewTopic deadLetterTopic(AnalyticsProperties props) {
        return TopicBuilder.name(props.kafka().dlqTopic()).partitions(1).replicas(1).build();
    }

    /**
     * Infrastructure failures (ClickHouse down, simulated crash) are retried forever with a fixed delay:
     * the offset is not committed, so no event is skipped. Poison messages never reach this handler;
     * the consumer routes them to the DLQ itself.
     */
    @Bean
    DefaultErrorHandler kafkaErrorHandler(AnalyticsProperties props) {
        return new DefaultErrorHandler(new FixedBackOff(props.kafka().retryIntervalMs(), FixedBackOff.UNLIMITED_ATTEMPTS));
    }
}

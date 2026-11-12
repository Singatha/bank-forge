package com.bankcore.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.util.backoff.FixedBackOff;

@Configuration(proxyBeanMethods = false)
@EnableKafka
@EnableScheduling
@EnableConfigurationProperties(MessagingProperties.class)
@ConditionalOnProperty(prefix = "bankcore.messaging", name = "enabled", havingValue = "true")
public class KafkaMessagingConfiguration {

    @Bean
    NewTopic transferEventsTopic(MessagingProperties properties) {
        return TopicBuilder.name(properties.transferTopic()).partitions(3).replicas(1).build();
    }

    @Bean
    NewTopic transferEventsDeadLetterTopic(MessagingProperties properties) {
        return TopicBuilder.name(properties.deadLetterTopic()).partitions(3).replicas(1).build();
    }

    @Bean
    CommonErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> kafkaTemplate,
                                         MessagingProperties properties) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                kafkaTemplate,
                (record, exception) -> new TopicPartition(properties.deadLetterTopic(), record.partition()));
        return new DefaultErrorHandler(recoverer, new FixedBackOff(200L, 2L));
    }
}

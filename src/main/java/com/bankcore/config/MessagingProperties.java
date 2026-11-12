package com.bankcore.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("bankcore.messaging")
public record MessagingProperties(
        boolean enabled,
        String transferTopic,
        String deadLetterTopic,
        String notificationConsumerGroup,
        int publisherBatchSize,
        int maxPublishAttempts,
        Duration publishTimeout,
        Duration retryDelay,
        Duration processingTimeout,
        Duration publisherFixedDelay,
        Duration publisherInitialDelay) {
}

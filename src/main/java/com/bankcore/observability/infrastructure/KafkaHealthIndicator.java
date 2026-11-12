package com.bankcore.observability.infrastructure;

import java.util.concurrent.TimeUnit;

import org.apache.kafka.clients.admin.AdminClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.stereotype.Component;

@Component("kafkaHealthIndicator")
@ConditionalOnProperty(prefix = "bankcore.messaging", name = "enabled", havingValue = "true")
public class KafkaHealthIndicator implements HealthIndicator {

    private final KafkaAdmin kafkaAdmin;

    public KafkaHealthIndicator(KafkaAdmin kafkaAdmin) {
        this.kafkaAdmin = kafkaAdmin;
    }

    @Override
    public Health health() {
        try (AdminClient adminClient = AdminClient.create(kafkaAdmin.getConfigurationProperties())) {
            String clusterId = adminClient.describeCluster().clusterId().get(2, TimeUnit.SECONDS);
            return Health.up().withDetail("clusterId", clusterId).build();
        } catch (Exception exception) {
            return Health.down()
                    .withDetail("error", exception.getClass().getSimpleName())
                    .build();
        }
    }
}

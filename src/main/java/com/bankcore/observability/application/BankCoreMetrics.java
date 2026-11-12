package com.bankcore.observability.application;

import java.time.Duration;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

@Component
public class BankCoreMetrics {

    private final MeterRegistry meterRegistry;

    public BankCoreMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public long startTimer() {
        return System.nanoTime();
    }

    public void transferCompleted(long startedAt) {
        counter("bankcore.transfers", "outcome", "completed").increment();
        transferTimer("completed").record(elapsed(startedAt));
    }

    public void transferFailed(long startedAt) {
        counter("bankcore.transfers", "outcome", "failed").increment();
        transferTimer("failed").record(elapsed(startedAt));
    }

    public void outboxPublication(String outcome) {
        counter("bankcore.outbox.events", "outcome", outcome).increment();
    }

    public void kafkaConsumer(String outcome) {
        counter("bankcore.kafka.consumer.events", "outcome", outcome).increment();
    }

    public void rateLimitDecision(String policy, String outcome) {
        Counter.builder("bankcore.rate.limit.decisions")
                .description("Rate-limit decisions by bounded policy and outcome")
                .tag("policy", policy)
                .tag("outcome", outcome)
                .register(meterRegistry)
                .increment();
    }

    private Counter counter(String name, String tagName, String tagValue) {
        return Counter.builder(name)
                .tag(tagName, tagValue)
                .register(meterRegistry);
    }

    private Timer transferTimer(String outcome) {
        return Timer.builder("bankcore.transfer.duration")
                .description("Time spent executing terminal transfer attempts")
                .tag("outcome", outcome)
                .publishPercentileHistogram()
                .register(meterRegistry);
    }

    private Duration elapsed(long startedAt) {
        return Duration.ofNanos(System.nanoTime() - startedAt);
    }
}

package com.bankcore.shared.api;

import java.time.Instant;

public record HealthResponse(String status, String application, Instant timestamp) {
}


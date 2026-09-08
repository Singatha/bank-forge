package com.bankcore.shared.api;

import java.time.Clock;
import java.time.Instant;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(path = "/api/v1/health", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Health")
public class HealthController {

    private final String applicationName;
    private final Clock clock;

    public HealthController(@Value("${spring.application.name}") String applicationName, Clock clock) {
        this.applicationName = applicationName;
        this.clock = clock;
    }

    @GetMapping
    @Operation(summary = "Check whether the BankCore API process is available")
    public HealthResponse health() {
        return new HealthResponse("UP", applicationName, Instant.now(clock));
    }
}


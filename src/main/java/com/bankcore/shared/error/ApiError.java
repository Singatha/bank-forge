package com.bankcore.shared.error;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;

public record ApiError(
        Instant timestamp,
        int status,
        String code,
        String message,
        String path,
        UUID requestId,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<FieldViolation> errors) {

    public ApiError {
        errors = errors == null ? List.of() : List.copyOf(errors);
    }
}


package com.bankcore.transfer.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.bankcore.transfer.domain.Transfer;

public record TransferResponse(
        UUID id,
        String reference,
        UUID sourceAccountId,
        UUID destinationAccountId,
        BigDecimal amount,
        String currency,
        String status,
        String description,
        Instant createdAt,
        Instant completedAt,
        String failureReason) {

    public static TransferResponse from(Transfer transfer) {
        return new TransferResponse(
                transfer.getId(),
                transfer.getReference(),
                transfer.getSourceAccountId(),
                transfer.getDestinationAccountId(),
                transfer.getAmount(),
                transfer.getCurrency().name(),
                transfer.getStatus().name(),
                transfer.getDescription(),
                transfer.getCreatedAt(),
                transfer.getCompletedAt(),
                transfer.getFailureReason());
    }
}

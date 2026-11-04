package com.bankcore.transfer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.bankcore.account.domain.Currency;
import com.bankcore.transfer.domain.Transfer;
import com.bankcore.transfer.domain.TransferException;
import com.bankcore.transfer.domain.TransferStatus;

class TransferStateTest {

    @Test
    void completesOnlyThroughProcessing() {
        Transfer transfer = transfer();
        Instant completedAt = Instant.parse("2026-11-04T12:00:00Z");

        transfer.startProcessing();
        transfer.complete(completedAt);

        assertThat(transfer.getStatus()).isEqualTo(TransferStatus.COMPLETED);
        assertThat(transfer.getCompletedAt()).isEqualTo(completedAt);
    }

    @Test
    void processingTransferCanFail() {
        Transfer transfer = transfer();

        transfer.startProcessing();
        transfer.fail("INSUFFICIENT_FUNDS");

        assertThat(transfer.getStatus()).isEqualTo(TransferStatus.FAILED);
        assertThat(transfer.getFailureReason()).isEqualTo("INSUFFICIENT_FUNDS");
    }

    @Test
    void rejectsInvalidAndTerminalTransitions() {
        Transfer transfer = transfer();

        assertThatThrownBy(() -> transfer.complete(Instant.now()))
                .isInstanceOf(TransferException.class)
                .hasMessageContaining("PENDING to COMPLETED");

        transfer.startProcessing();
        transfer.fail("TEST_FAILURE");

        assertThatThrownBy(transfer::startProcessing)
                .isInstanceOf(TransferException.class)
                .hasMessageContaining("FAILED to PROCESSING");
    }

    private Transfer transfer() {
        return new Transfer(
                UUID.randomUUID(),
                "TR123456789012345678",
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                new BigDecimal("25.00"),
                Currency.ZAR,
                "Test transfer",
                Instant.parse("2026-11-04T11:00:00Z"));
    }
}

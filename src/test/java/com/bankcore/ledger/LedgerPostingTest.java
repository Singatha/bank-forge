package com.bankcore.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.bankcore.account.domain.Currency;
import com.bankcore.ledger.domain.EntryType;
import com.bankcore.ledger.domain.LedgerEntry;
import com.bankcore.ledger.domain.LedgerPosting;
import com.bankcore.transfer.domain.Transfer;

class LedgerPostingTest {

    private static final Instant NOW = Instant.parse("2026-11-11T12:00:00Z");

    @Test
    void completedTransferCreatesOneBalancedDebitAndCredit() {
        Transfer transfer = completedTransfer();

        LedgerPosting posting = LedgerPosting.forCompletedTransfer(transfer, NOW);

        assertThat(posting.entries()).hasSize(2);
        assertThat(posting.entries()).extracting(LedgerEntry::getEntryType)
                .containsExactly(EntryType.DEBIT, EntryType.CREDIT);
        assertThat(posting.entries()).extracting(LedgerEntry::getAmount)
                .allSatisfy(amount -> assertThat(amount).isEqualByComparingTo("125.50"));
        assertThat(posting.entries()).extracting(LedgerEntry::getTransferId)
                .containsOnly(transfer.getId());
    }

    @Test
    void rejectsPostingWithUnequalDebitAndCredit() {
        UUID transferId = UUID.randomUUID();
        List<LedgerEntry> entries = List.of(
                entry(transferId, UUID.randomUUID(), EntryType.DEBIT, "125.50"),
                entry(transferId, UUID.randomUUID(), EntryType.CREDIT, "125.49"));

        assertThatThrownBy(() -> LedgerPosting.requireBalanced(entries))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Ledger debit and credit must balance for one transfer.");
    }

    @Test
    void rejectsPostingWithoutExactlyOneEntryOfEachType() {
        UUID transferId = UUID.randomUUID();
        List<LedgerEntry> entries = List.of(
                entry(transferId, UUID.randomUUID(), EntryType.DEBIT, "125.50"),
                entry(transferId, UUID.randomUUID(), EntryType.DEBIT, "125.50"));

        assertThatThrownBy(() -> LedgerPosting.requireBalanced(entries))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("A transfer posting requires one debit and one credit.");
    }

    @Test
    void rejectsPostingForTransferThatIsNotCompleted() {
        Transfer transfer = transfer();
        transfer.startProcessing();

        assertThatThrownBy(() -> LedgerPosting.forCompletedTransfer(transfer, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Only completed transfers can be posted to the ledger.");
    }

    private LedgerEntry entry(UUID transferId, UUID accountId, EntryType type, String amount) {
        return new LedgerEntry(UUID.randomUUID(), transferId, accountId, type,
                new BigDecimal(amount), Currency.ZAR, NOW);
    }

    private Transfer completedTransfer() {
        Transfer transfer = transfer();
        transfer.startProcessing();
        transfer.complete(NOW);
        return transfer;
    }

    private Transfer transfer() {
        return new Transfer(
                UUID.randomUUID(),
                "TRTESTREFERENCE123456",
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                new BigDecimal("125.50"),
                Currency.ZAR,
                "Ledger test",
                NOW.minusSeconds(1));
    }
}

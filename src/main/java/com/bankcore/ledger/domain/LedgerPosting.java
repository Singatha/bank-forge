package com.bankcore.ledger.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.bankcore.transfer.domain.Transfer;
import com.bankcore.transfer.domain.TransferStatus;

public final class LedgerPosting {

    private final List<LedgerEntry> entries;

    private LedgerPosting(List<LedgerEntry> entries) {
        this.entries = List.copyOf(entries);
        requireBalanced(this.entries);
    }

    public static LedgerPosting forCompletedTransfer(Transfer transfer, Instant createdAt) {
        Objects.requireNonNull(transfer);
        if (transfer.getStatus() != TransferStatus.COMPLETED) {
            throw new IllegalArgumentException("Only completed transfers can be posted to the ledger.");
        }
        return new LedgerPosting(List.of(
                new LedgerEntry(UUID.randomUUID(), transfer.getId(), transfer.getSourceAccountId(),
                        EntryType.DEBIT, transfer.getAmount(), transfer.getCurrency(), createdAt),
                new LedgerEntry(UUID.randomUUID(), transfer.getId(), transfer.getDestinationAccountId(),
                        EntryType.CREDIT, transfer.getAmount(), transfer.getCurrency(), createdAt)));
    }

    public static void requireBalanced(List<LedgerEntry> entries) {
        Objects.requireNonNull(entries);
        if (entries.size() != 2) {
            throw new IllegalArgumentException("A transfer posting must contain exactly two ledger entries.");
        }

        LedgerEntry debit = singleEntry(entries, EntryType.DEBIT);
        LedgerEntry credit = singleEntry(entries, EntryType.CREDIT);
        if (!debit.getTransferId().equals(credit.getTransferId())
                || debit.getAccountId().equals(credit.getAccountId())
                || debit.getAmount().compareTo(credit.getAmount()) != 0
                || debit.getCurrency() != credit.getCurrency()) {
            throw new IllegalArgumentException("Ledger debit and credit must balance for one transfer.");
        }
    }

    public List<LedgerEntry> entries() {
        return entries;
    }

    private static LedgerEntry singleEntry(List<LedgerEntry> entries, EntryType entryType) {
        List<LedgerEntry> matches = entries.stream()
                .filter(entry -> entry.getEntryType() == entryType)
                .toList();
        if (matches.size() != 1) {
            throw new IllegalArgumentException("A transfer posting requires one debit and one credit.");
        }
        return matches.getFirst();
    }
}

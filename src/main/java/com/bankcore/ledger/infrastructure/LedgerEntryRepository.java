package com.bankcore.ledger.infrastructure;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.bankcore.ledger.application.AccountTransaction;
import com.bankcore.ledger.domain.LedgerEntry;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, UUID> {

    List<LedgerEntry> findAllByTransferIdOrderByCreatedAtAsc(UUID transferId);

    @Query("""
            select new com.bankcore.ledger.application.AccountTransaction(
                entry.id,
                entry.transferId,
                transfer.reference,
                entry.accountId,
                entry.entryType,
                entry.amount,
                entry.currency,
                transfer.description,
                entry.createdAt)
            from LedgerEntry entry
            join Transfer transfer on transfer.id = entry.transferId
            where entry.accountId = :accountId
            order by entry.createdAt desc, entry.id desc
            """)
    List<AccountTransaction> findAccountTransactions(@Param("accountId") UUID accountId);
}

package com.bankcore.ledger.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.bankcore.account.application.AccountActor;
import com.bankcore.account.application.AccountService;
import com.bankcore.ledger.domain.LedgerPosting;
import com.bankcore.ledger.infrastructure.LedgerEntryRepository;
import com.bankcore.transfer.domain.Transfer;

@Service
public class LedgerService {

    private final LedgerEntryRepository ledgerEntryRepository;
    private final AccountService accountService;

    public LedgerService(LedgerEntryRepository ledgerEntryRepository, AccountService accountService) {
        this.ledgerEntryRepository = ledgerEntryRepository;
        this.accountService = accountService;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void postCompletedTransfer(Transfer transfer, Instant createdAt) {
        LedgerPosting posting = LedgerPosting.forCompletedTransfer(transfer, createdAt);
        ledgerEntryRepository.saveAll(posting.entries());
    }

    @Transactional(readOnly = true)
    public List<AccountTransaction> accountTransactions(UUID accountId, AccountActor actor) {
        accountService.get(accountId, actor);
        return ledgerEntryRepository.findAccountTransactions(accountId);
    }
}

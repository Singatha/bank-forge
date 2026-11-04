package com.bankcore.transfer.application;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bankcore.account.application.AccountActor;
import com.bankcore.account.domain.Account;
import com.bankcore.account.domain.AccountException;
import com.bankcore.account.domain.Currency;
import com.bankcore.account.infrastructure.AccountRepository;
import com.bankcore.customer.infrastructure.CustomerRepository;
import com.bankcore.transfer.domain.Transfer;
import com.bankcore.transfer.domain.TransferException;
import com.bankcore.transfer.infrastructure.TransferReferenceGenerator;
import com.bankcore.transfer.infrastructure.TransferRepository;

@Service
public class TransferService {

    private final TransferRepository transferRepository;
    private final AccountRepository accountRepository;
    private final CustomerRepository customerRepository;
    private final TransferReferenceGenerator referenceGenerator;
    private final Clock clock;

    public TransferService(TransferRepository transferRepository,
                           AccountRepository accountRepository,
                           CustomerRepository customerRepository,
                           TransferReferenceGenerator referenceGenerator,
                           Clock clock) {
        this.transferRepository = transferRepository;
        this.accountRepository = accountRepository;
        this.customerRepository = customerRepository;
        this.referenceGenerator = referenceGenerator;
        this.clock = clock;
    }

    @Transactional(noRollbackFor = TransferException.class)
    public Transfer create(AccountActor actor, UUID sourceAccountId, UUID destinationAccountId,
                           BigDecimal amount, Currency currency, String description) {
        if (sourceAccountId.equals(destinationAccountId)) {
            throw TransferException.sameAccount();
        }

        Account source = accountRepository.findById(sourceAccountId)
                .orElseThrow(AccountException::accountNotFound);
        authorizeSourceOwnership(source, actor.userId());
        Account destination = accountRepository.findById(destinationAccountId)
                .orElseThrow(AccountException::accountNotFound);

        Instant now = Instant.now(clock);
        Transfer transfer = new Transfer(
                UUID.randomUUID(),
                referenceGenerator.generate(),
                sourceAccountId,
                destinationAccountId,
                actor.userId(),
                amount,
                currency,
                description,
                now);
        transferRepository.save(transfer);
        transfer.startProcessing();

        try {
            source.validateTransferDebit(amount, currency);
            destination.validateTransferCredit(amount, currency);
        } catch (AccountException exception) {
            transfer.fail(exception.code());
            transferRepository.saveAndFlush(transfer);
            throw TransferException.businessFailure(exception);
        }

        source.applyTransferDebit(amount, now);
        destination.applyTransferCredit(amount, now);
        transfer.complete(now);
        return transferRepository.saveAndFlush(transfer);
    }

    @Transactional(readOnly = true)
    public List<Transfer> list(AccountActor actor) {
        if (actor.isStaff()) {
            return transferRepository.findAllByOrderByCreatedAtDesc();
        }
        List<UUID> accountIds = ownedAccountIds(actor.userId());
        return accountIds.isEmpty() ? List.of() : transferRepository.findAccessibleTransfers(accountIds);
    }

    @Transactional(readOnly = true)
    public Transfer get(UUID transferId, AccountActor actor) {
        Transfer transfer = transferRepository.findById(transferId)
                .orElseThrow(TransferException::transferNotFound);
        if (actor.isStaff()) {
            return transfer;
        }
        List<UUID> accountIds = ownedAccountIds(actor.userId());
        if (accountIds.contains(transfer.getSourceAccountId())
                || accountIds.contains(transfer.getDestinationAccountId())) {
            return transfer;
        }
        throw TransferException.transferNotFound();
    }

    private void authorizeSourceOwnership(Account source, UUID userId) {
        boolean owner = customerRepository.findById(source.getCustomerId())
                .map(customer -> customer.getUserId().equals(userId))
                .orElse(false);
        if (!owner) {
            throw AccountException.accountNotFound();
        }
    }

    private List<UUID> ownedAccountIds(UUID userId) {
        return customerRepository.findByUserId(userId)
                .map(customer -> accountRepository.findAllByCustomerIdOrderByCreatedAtAsc(customer.getId())
                        .stream()
                        .map(Account::getId)
                        .toList())
                .orElseGet(List::of);
    }
}

package com.bankcore.transfer.application;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bankcore.account.application.AccountActor;
import com.bankcore.account.domain.Account;
import com.bankcore.account.domain.AccountException;
import com.bankcore.account.domain.Currency;
import com.bankcore.account.infrastructure.AccountRepository;
import com.bankcore.audit.application.AuditService;
import com.bankcore.audit.domain.AuditAction;
import com.bankcore.audit.domain.AuditResourceType;
import com.bankcore.customer.infrastructure.CustomerRepository;
import com.bankcore.ledger.application.LedgerService;
import com.bankcore.transfer.domain.Transfer;
import com.bankcore.transfer.domain.TransferException;
import com.bankcore.transfer.domain.IdempotencyException;
import com.bankcore.transfer.domain.IdempotencyOutcome;
import com.bankcore.transfer.domain.IdempotencyRecord;
import com.bankcore.transfer.infrastructure.TransferReferenceGenerator;
import com.bankcore.transfer.infrastructure.TransferRepository;

@Service
public class TransferService {

    private static final Logger LOGGER = LoggerFactory.getLogger(TransferService.class);

    private final TransferRepository transferRepository;
    private final AccountRepository accountRepository;
    private final CustomerRepository customerRepository;
    private final TransferReferenceGenerator referenceGenerator;
    private final LedgerService ledgerService;
    private final IdempotencyService idempotencyService;
    private final TransferRequestFingerprint requestFingerprint;
    private final AuditService auditService;
    private final Clock clock;

    public TransferService(TransferRepository transferRepository,
                           AccountRepository accountRepository,
                           CustomerRepository customerRepository,
                           TransferReferenceGenerator referenceGenerator,
                           LedgerService ledgerService,
                           IdempotencyService idempotencyService,
                           TransferRequestFingerprint requestFingerprint,
                           AuditService auditService,
                           Clock clock) {
        this.transferRepository = transferRepository;
        this.accountRepository = accountRepository;
        this.customerRepository = customerRepository;
        this.referenceGenerator = referenceGenerator;
        this.ledgerService = ledgerService;
        this.idempotencyService = idempotencyService;
        this.requestFingerprint = requestFingerprint;
        this.auditService = auditService;
        this.clock = clock;
    }

    @Transactional(noRollbackFor = TransferException.class)
    public TransferResult create(AccountActor actor, String idempotencyKey,
                                 UUID sourceAccountId, UUID destinationAccountId,
                                 BigDecimal amount, Currency currency, String description) {
        idempotencyService.validateKey(idempotencyKey);
        if (sourceAccountId.equals(destinationAccountId)) {
            throw TransferException.sameAccount();
        }

        String fingerprint = requestFingerprint.hash(
                sourceAccountId, destinationAccountId, amount, currency, description);
        var attempt = idempotencyService.begin(actor.userId(), idempotencyKey, fingerprint);
        if (attempt.replayed()) {
            return replay(attempt.record());
        }

        Map<UUID, Account> lockedAccounts = accountRepository
                .findAllByIdForUpdate(List.of(sourceAccountId, destinationAccountId))
                .stream()
                .collect(java.util.stream.Collectors.toMap(Account::getId, account -> account));
        Account source = java.util.Optional.ofNullable(lockedAccounts.get(sourceAccountId))
                .orElseThrow(AccountException::accountNotFound);
        authorizeSourceOwnership(source, actor.userId());
        Account destination = java.util.Optional.ofNullable(lockedAccounts.get(destinationAccountId))
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
            attempt.record().fail(transfer.getId(), exception.status().value(),
                    exception.code(), exception.getMessage(), now);
            auditService.record(actor.userId(), AuditAction.TRANSFER_FAILED, AuditResourceType.TRANSFER,
                    transfer.getId(), Map.of(
                            "reference", transfer.getReference(),
                            "reason", exception.code()));
            transferRepository.saveAndFlush(transfer);
            LOGGER.warn("transfer_failed transferId={} sourceAccountId={} destinationAccountId={} reason={}",
                    transfer.getId(), sourceAccountId, destinationAccountId, exception.code());
            throw TransferException.businessFailure(exception);
        }

        source.applyTransferDebit(amount, now);
        destination.applyTransferCredit(amount, now);
        transfer.complete(now);
        ledgerService.postCompletedTransfer(transfer, now);
        attempt.record().complete(transfer.getId(), now);
        auditService.record(actor.userId(), AuditAction.TRANSFER_COMPLETED, AuditResourceType.TRANSFER,
                transfer.getId(), Map.of("reference", transfer.getReference()));
        Transfer completed = transferRepository.saveAndFlush(transfer);
        LOGGER.info("transfer_completed transferId={} sourceAccountId={} destinationAccountId={}",
                transfer.getId(), sourceAccountId, destinationAccountId);
        return new TransferResult(completed, false);
    }

    @Transactional(readOnly = true)
    public Page<Transfer> list(AccountActor actor, Pageable pageable) {
        if (actor.isStaff()) {
            return transferRepository.findAll(pageable);
        }
        List<UUID> accountIds = ownedAccountIds(actor.userId());
        return accountIds.isEmpty()
                ? Page.empty(pageable)
                : transferRepository.findAccessibleTransfers(accountIds, pageable);
    }

    private TransferResult replay(IdempotencyRecord record) {
        if (record.getOutcome() == IdempotencyOutcome.PROCESSING) {
            throw IdempotencyException.processing();
        }
        if (record.getOutcome() == IdempotencyOutcome.FAILED) {
            LOGGER.info("transfer_idempotency_replay transferId={} outcome=FAILED", record.getTransferId());
            throw TransferException.replayedFailure(
                    record.getResponseStatus(), record.getErrorCode(), record.getErrorMessage());
        }
        Transfer transfer = transferRepository.findById(record.getTransferId())
                .orElseThrow(() -> new IllegalStateException("Idempotency transfer is missing"));
        LOGGER.info("transfer_idempotency_replay transferId={} outcome=COMPLETED", transfer.getId());
        return new TransferResult(transfer, true);
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

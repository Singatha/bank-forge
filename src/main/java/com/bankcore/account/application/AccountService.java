package com.bankcore.account.application;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import com.bankcore.account.domain.Account;
import com.bankcore.account.domain.AccountException;
import com.bankcore.account.domain.AccountType;
import com.bankcore.account.domain.Currency;
import com.bankcore.account.infrastructure.AccountNumberGenerator;
import com.bankcore.account.infrastructure.AccountRepository;
import com.bankcore.audit.application.AuditService;
import com.bankcore.audit.domain.AuditAction;
import com.bankcore.audit.domain.AuditResourceType;
import com.bankcore.customer.domain.Customer;
import com.bankcore.customer.domain.CustomerException;
import com.bankcore.customer.infrastructure.CustomerRepository;

@Service
public class AccountService {

    private final AccountRepository accountRepository;
    private final CustomerRepository customerRepository;
    private final AccountNumberGenerator accountNumberGenerator;
    private final AuditService auditService;
    private final Clock clock;

    public AccountService(AccountRepository accountRepository,
                          CustomerRepository customerRepository,
                          AccountNumberGenerator accountNumberGenerator,
                          AuditService auditService,
                          Clock clock) {
        this.accountRepository = accountRepository;
        this.customerRepository = customerRepository;
        this.accountNumberGenerator = accountNumberGenerator;
        this.auditService = auditService;
        this.clock = clock;
    }

    @Transactional
    public Account create(UUID userId, AccountType accountType, Currency currency) {
        Customer customer = customerRepository.findByUserId(userId)
                .orElseThrow(CustomerException::profileRequired);
        if (!customer.isActive()) {
            throw CustomerException.inactiveCustomer();
        }
        Account account = new Account(
                UUID.randomUUID(),
                accountNumberGenerator.generate(),
                customer.getId(),
                accountType,
                currency,
                Instant.now(clock));
        accountRepository.save(account);
        auditService.record(userId, AuditAction.ACCOUNT_CREATED, AuditResourceType.ACCOUNT,
                account.getId(), java.util.Map.of(
                        "accountType", accountType.name(),
                        "currency", currency.name()));
        return accountRepository.saveAndFlush(account);
    }

    @Transactional(readOnly = true)
    public Page<Account> list(AccountActor actor, Pageable pageable) {
        if (actor.isStaff()) {
            return accountRepository.findAll(pageable);
        }
        return customerRepository.findByUserId(actor.userId())
                .map(customer -> accountRepository.findAllByCustomerId(customer.getId(), pageable))
                .orElseGet(() -> Page.empty(pageable));
    }

    @Transactional(readOnly = true)
    public Account get(UUID accountId, AccountActor actor) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(AccountException::accountNotFound);
        authorizeRead(account, actor);
        return account;
    }

    @Transactional
    public Account applyDevelopmentFunding(UUID accountId, AccountActor actor,
                                           BigDecimal amount, Currency currency) {
        Account account = accountRepository.findByIdForUpdate(accountId)
                .orElseThrow(AccountException::accountNotFound);
        authorizeFunding(account, actor);
        account.applyDevelopmentFunding(amount, currency, Instant.now(clock));
        return account;
    }

    private void authorizeRead(Account account, AccountActor actor) {
        if (actor.isStaff() || isOwner(account, actor.userId())) {
            return;
        }
        throw AccountException.accountNotFound();
    }

    private void authorizeFunding(Account account, AccountActor actor) {
        if (actor.hasRole("ADMIN") || isOwner(account, actor.userId())) {
            return;
        }
        throw AccountException.accountNotFound();
    }

    private boolean isOwner(Account account, UUID userId) {
        return customerRepository.findById(account.getCustomerId())
                .map(customer -> customer.getUserId().equals(userId))
                .orElse(false);
    }
}

package com.bankcore.account.application;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bankcore.account.domain.Account;
import com.bankcore.account.domain.AccountException;
import com.bankcore.account.domain.AccountType;
import com.bankcore.account.domain.Currency;
import com.bankcore.account.infrastructure.AccountNumberGenerator;
import com.bankcore.account.infrastructure.AccountRepository;
import com.bankcore.customer.domain.Customer;
import com.bankcore.customer.domain.CustomerException;
import com.bankcore.customer.infrastructure.CustomerRepository;

@Service
public class AccountService {

    private final AccountRepository accountRepository;
    private final CustomerRepository customerRepository;
    private final AccountNumberGenerator accountNumberGenerator;
    private final Clock clock;

    public AccountService(AccountRepository accountRepository,
                          CustomerRepository customerRepository,
                          AccountNumberGenerator accountNumberGenerator,
                          Clock clock) {
        this.accountRepository = accountRepository;
        this.customerRepository = customerRepository;
        this.accountNumberGenerator = accountNumberGenerator;
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
        return accountRepository.saveAndFlush(account);
    }

    @Transactional(readOnly = true)
    public List<Account> list(AccountActor actor) {
        if (actor.isStaff()) {
            return accountRepository.findAllByOrderByCreatedAtAsc();
        }
        return customerRepository.findByUserId(actor.userId())
                .map(customer -> accountRepository.findAllByCustomerIdOrderByCreatedAtAsc(customer.getId()))
                .orElseGet(List::of);
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

package com.bankcore.account.infrastructure;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import com.bankcore.account.domain.Account;

public interface AccountRepository extends JpaRepository<Account, UUID> {

    List<Account> findAllByCustomerIdOrderByCreatedAtAsc(UUID customerId);

    Page<Account> findAllByCustomerId(UUID customerId, Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select account from Account account where account.id = :accountId")
    Optional<Account> findByIdForUpdate(@Param("accountId") UUID accountId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select account from Account account where account.id in :accountIds order by account.id")
    List<Account> findAllByIdForUpdate(@Param("accountIds") List<UUID> accountIds);
}

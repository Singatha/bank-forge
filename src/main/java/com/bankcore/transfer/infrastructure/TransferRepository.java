package com.bankcore.transfer.infrastructure;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.bankcore.transfer.domain.Transfer;

public interface TransferRepository extends JpaRepository<Transfer, UUID> {

    List<Transfer> findAllByOrderByCreatedAtDesc();

    @Query("select transfer from Transfer transfer "
            + "where transfer.sourceAccountId in :accountIds "
            + "or transfer.destinationAccountId in :accountIds "
            + "order by transfer.createdAt desc")
    List<Transfer> findAccessibleTransfers(@Param("accountIds") Collection<UUID> accountIds);
}

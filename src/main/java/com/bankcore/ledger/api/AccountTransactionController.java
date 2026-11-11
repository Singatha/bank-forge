package com.bankcore.ledger.api;

import java.util.HashSet;
import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.bankcore.account.application.AccountActor;
import com.bankcore.ledger.application.LedgerService;

@RestController
@RequestMapping("/api/v1/accounts/{accountId}/transactions")
@Tag(name = "Account transactions")
@SecurityRequirement(name = "bearerAuth")
public class AccountTransactionController {

    private final LedgerService ledgerService;

    public AccountTransactionController(LedgerService ledgerService) {
        this.ledgerService = ledgerService;
    }

    @GetMapping
    @Operation(summary = "List immutable ledger transactions for an owned account; staff have read access")
    public List<AccountTransactionResponse> list(@PathVariable UUID accountId,
                                                 @AuthenticationPrincipal Jwt jwt) {
        return ledgerService.accountTransactions(accountId, actor(jwt)).stream()
                .map(AccountTransactionResponse::from)
                .toList();
    }

    private AccountActor actor(Jwt jwt) {
        List<String> claimedRoles = jwt.getClaimAsStringList("roles");
        return new AccountActor(
                UUID.fromString(jwt.getSubject()),
                claimedRoles == null ? new HashSet<>() : new HashSet<>(claimedRoles));
    }
}

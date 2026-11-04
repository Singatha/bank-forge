package com.bankcore.account.api;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.bankcore.account.application.AccountService;

@RestController
@RequestMapping("/api/v1/accounts")
@Tag(name = "Accounts")
@SecurityRequirement(name = "bearerAuth")
public class AccountController {

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('CUSTOMER')")
    @Operation(summary = "Open a simulated account for the authenticated customer")
    public AccountResponse create(@AuthenticationPrincipal Jwt jwt,
                                  @Valid @RequestBody CreateAccountRequest request) {
        return AccountResponse.from(accountService.create(
                UUID.fromString(jwt.getSubject()), request.accountType(), request.currency()));
    }

    @GetMapping
    @Operation(summary = "List owned accounts, or all accounts for SUPPORT and ADMIN")
    public List<AccountResponse> list(@AuthenticationPrincipal Jwt jwt) {
        return accountService.list(AccountControllerSupport.actor(jwt)).stream()
                .map(AccountResponse::from)
                .toList();
    }

    @GetMapping("/{accountId}")
    @Operation(summary = "Get an owned account; SUPPORT and ADMIN have read access")
    public AccountResponse get(@PathVariable UUID accountId, @AuthenticationPrincipal Jwt jwt) {
        return AccountResponse.from(accountService.get(accountId, AccountControllerSupport.actor(jwt)));
    }

    @GetMapping("/{accountId}/balance")
    @Operation(summary = "Get an owned account balance; SUPPORT and ADMIN have read access")
    public AccountBalanceResponse balance(@PathVariable UUID accountId, @AuthenticationPrincipal Jwt jwt) {
        return AccountBalanceResponse.from(accountService.get(accountId, AccountControllerSupport.actor(jwt)));
    }
}

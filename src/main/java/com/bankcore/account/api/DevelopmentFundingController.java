package com.bankcore.account.api;

import java.util.UUID;

import jakarta.validation.Valid;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.bankcore.account.application.AccountService;

@RestController
@RequestMapping("/api/v1/development/accounts")
@ConditionalOnProperty(prefix = "bankcore.features", name = "development-funding-enabled", havingValue = "true")
@Tag(name = "Development funding")
@SecurityRequirement(name = "bearerAuth")
public class DevelopmentFundingController {

    private final AccountService accountService;

    public DevelopmentFundingController(AccountService accountService) {
        this.accountService = accountService;
    }

    @PostMapping("/{accountId}/fund")
    @Operation(summary = "Credit simulated funds; available only when explicitly enabled")
    public AccountBalanceResponse fund(@PathVariable UUID accountId,
                                       @AuthenticationPrincipal Jwt jwt,
                                       @Valid @RequestBody DevelopmentFundingRequest request) {
        return AccountBalanceResponse.from(accountService.applyDevelopmentFunding(
                accountId,
                AccountControllerSupport.actor(jwt),
                request.amount(),
                request.currency()));
    }
}

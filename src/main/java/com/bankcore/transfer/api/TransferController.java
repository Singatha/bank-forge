package com.bankcore.transfer.api;

import java.util.HashSet;
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

import com.bankcore.account.application.AccountActor;
import com.bankcore.transfer.application.TransferService;

@RestController
@RequestMapping("/api/v1/transfers")
@Tag(name = "Transfers")
@SecurityRequirement(name = "bearerAuth")
public class TransferController {

    private final TransferService transferService;

    public TransferController(TransferService transferService) {
        this.transferService = transferService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('CUSTOMER')")
    @Operation(summary = "Transfer simulated funds from an owned account")
    public TransferResponse create(@AuthenticationPrincipal Jwt jwt,
                                   @Valid @RequestBody CreateTransferRequest request) {
        return TransferResponse.from(transferService.create(
                actor(jwt),
                request.sourceAccountId(),
                request.destinationAccountId(),
                request.amount(),
                request.currency(),
                request.description()));
    }

    @GetMapping
    @Operation(summary = "List transfers visible through owned accounts; staff can list all")
    public List<TransferResponse> list(@AuthenticationPrincipal Jwt jwt) {
        return transferService.list(actor(jwt)).stream()
                .map(TransferResponse::from)
                .toList();
    }

    @GetMapping("/{transferId}")
    @Operation(summary = "Get a transfer visible through an owned account; staff have read access")
    public TransferResponse get(@PathVariable UUID transferId, @AuthenticationPrincipal Jwt jwt) {
        return TransferResponse.from(transferService.get(transferId, actor(jwt)));
    }

    private AccountActor actor(Jwt jwt) {
        List<String> claimedRoles = jwt.getClaimAsStringList("roles");
        return new AccountActor(
                UUID.fromString(jwt.getSubject()),
                claimedRoles == null ? new HashSet<>() : new HashSet<>(claimedRoles));
    }
}

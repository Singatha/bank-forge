package com.bankcore.customer.api;

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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.bankcore.customer.application.CustomerService;

@RestController
@RequestMapping("/api/v1/customers")
@Tag(name = "Customers")
@SecurityRequirement(name = "bearerAuth")
public class CustomerController {

    private final CustomerService customerService;

    public CustomerController(CustomerService customerService) {
        this.customerService = customerService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('CUSTOMER')")
    @Operation(summary = "Create the authenticated user's simulated customer profile")
    public CustomerResponse create(@AuthenticationPrincipal Jwt jwt,
                                   @Valid @RequestBody CreateCustomerProfileRequest request) {
        return CustomerResponse.from(customerService.createProfile(
                UUID.fromString(jwt.getSubject()),
                jwt.getClaimAsString("email"),
                request.firstName(),
                request.lastName(),
                request.phoneNumber()));
    }

    @GetMapping("/me")
    @PreAuthorize("hasRole('CUSTOMER')")
    @Operation(summary = "Get the authenticated user's customer profile")
    public CustomerResponse me(@AuthenticationPrincipal Jwt jwt) {
        return CustomerResponse.from(customerService.getOwnProfile(UUID.fromString(jwt.getSubject())));
    }
}

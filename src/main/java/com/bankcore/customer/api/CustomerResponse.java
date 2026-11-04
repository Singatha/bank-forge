package com.bankcore.customer.api;

import java.time.Instant;
import java.util.UUID;

import com.bankcore.customer.domain.Customer;

public record CustomerResponse(
        UUID id,
        String firstName,
        String lastName,
        String email,
        String phoneNumber,
        String status,
        Instant createdAt,
        Instant updatedAt) {

    public static CustomerResponse from(Customer customer) {
        return new CustomerResponse(
                customer.getId(),
                customer.getFirstName(),
                customer.getLastName(),
                customer.getEmail(),
                customer.getPhoneNumber(),
                customer.getStatus().name(),
                customer.getCreatedAt(),
                customer.getUpdatedAt());
    }
}

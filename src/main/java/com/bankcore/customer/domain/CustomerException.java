package com.bankcore.customer.domain;

import org.springframework.http.HttpStatus;

import com.bankcore.shared.error.ApiException;

public final class CustomerException extends ApiException {

    private CustomerException(HttpStatus status, String code, String message) {
        super(status, code, message);
    }

    public static CustomerException profileAlreadyExists() {
        return new CustomerException(HttpStatus.CONFLICT, "CUSTOMER_PROFILE_EXISTS",
                "A customer profile already exists for this user.");
    }

    public static CustomerException profileNotFound() {
        return new CustomerException(HttpStatus.NOT_FOUND, "CUSTOMER_PROFILE_NOT_FOUND",
                "The customer profile was not found.");
    }

    public static CustomerException profileRequired() {
        return new CustomerException(HttpStatus.CONFLICT, "CUSTOMER_PROFILE_REQUIRED",
                "Create a customer profile before opening an account.");
    }

    public static CustomerException inactiveCustomer() {
        return new CustomerException(HttpStatus.CONFLICT, "CUSTOMER_NOT_ACTIVE",
                "The customer profile must be active for this operation.");
    }
}

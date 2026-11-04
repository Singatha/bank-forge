package com.bankcore.customer.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bankcore.customer.domain.Customer;
import com.bankcore.customer.domain.CustomerException;
import com.bankcore.customer.infrastructure.CustomerRepository;

@Service
public class CustomerService {

    private final CustomerRepository customerRepository;
    private final Clock clock;

    public CustomerService(CustomerRepository customerRepository, Clock clock) {
        this.customerRepository = customerRepository;
        this.clock = clock;
    }

    @Transactional
    public Customer createProfile(UUID userId, String authenticatedEmail,
                                  String firstName, String lastName, String phoneNumber) {
        if (customerRepository.existsByUserId(userId)) {
            throw CustomerException.profileAlreadyExists();
        }
        Instant now = Instant.now(clock);
        Customer customer = new Customer(
                UUID.randomUUID(),
                userId,
                firstName.strip(),
                lastName.strip(),
                authenticatedEmail.strip().toLowerCase(Locale.ROOT),
                phoneNumber.strip(),
                now);
        return customerRepository.saveAndFlush(customer);
    }

    @Transactional(readOnly = true)
    public Customer getOwnProfile(UUID userId) {
        return customerRepository.findByUserId(userId)
                .orElseThrow(CustomerException::profileNotFound);
    }
}

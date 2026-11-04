package com.bankcore.account.infrastructure;

import java.util.Locale;
import java.util.UUID;

import org.springframework.stereotype.Component;

@Component
public class AccountNumberGenerator {

    public String generate() {
        String randomPart = UUID.randomUUID().toString().replace("-", "")
                .substring(0, 14)
                .toUpperCase(Locale.ROOT);
        return "BC" + randomPart;
    }
}

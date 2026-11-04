package com.bankcore.transfer.infrastructure;

import java.util.Locale;
import java.util.UUID;

import org.springframework.stereotype.Component;

@Component
public class TransferReferenceGenerator {

    public String generate() {
        String randomPart = UUID.randomUUID().toString().replace("-", "")
                .substring(0, 18)
                .toUpperCase(Locale.ROOT);
        return "TR" + randomPart;
    }
}

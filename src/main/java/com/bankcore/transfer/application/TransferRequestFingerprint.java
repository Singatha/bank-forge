package com.bankcore.transfer.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.bankcore.account.domain.Currency;

@Component
public class TransferRequestFingerprint {

    public String hash(UUID sourceAccountId, UUID destinationAccountId, BigDecimal amount,
                       Currency currency, String description) {
        String normalizedDescription = description == null || description.isBlank()
                ? ""
                : description.strip();
        String canonicalRequest = String.join("\u001f",
                sourceAccountId.toString(),
                destinationAccountId.toString(),
                amount.setScale(2, RoundingMode.UNNECESSARY).toPlainString(),
                currency.name(),
                normalizedDescription);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonicalRequest.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}

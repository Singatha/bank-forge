package com.bankcore.account.api;

import java.util.HashSet;
import java.util.List;
import java.util.UUID;

import org.springframework.security.oauth2.jwt.Jwt;

import com.bankcore.account.application.AccountActor;

final class AccountControllerSupport {

    private AccountControllerSupport() {
    }

    static AccountActor actor(Jwt jwt) {
        List<String> claimedRoles = jwt.getClaimAsStringList("roles");
        return new AccountActor(
                UUID.fromString(jwt.getSubject()),
                claimedRoles == null ? new HashSet<>() : new HashSet<>(claimedRoles));
    }
}

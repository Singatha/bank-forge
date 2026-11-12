package com.bankcore.shared.web;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import org.springframework.security.oauth2.jwt.Jwt;

public final class JwtClaims {

    private JwtClaims() {
    }

    public static UUID subject(Jwt jwt) {
        return UUID.fromString(Objects.requireNonNull(jwt.getSubject(), "JWT subject is required"));
    }

    public static Set<String> roles(Jwt jwt) {
        List<String> claimedRoles = jwt.getClaimAsStringList("roles");
        return claimedRoles == null ? Set.of() : Set.copyOf(claimedRoles);
    }
}

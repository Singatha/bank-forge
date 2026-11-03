package com.bankcore.auth.api;

import java.util.List;
import java.util.UUID;

import org.springframework.security.oauth2.jwt.Jwt;

public record CurrentUserResponse(UUID id, String email, List<String> roles) {

    public static CurrentUserResponse from(Jwt jwt) {
        return new CurrentUserResponse(
                UUID.fromString(jwt.getSubject()),
                jwt.getClaimAsString("email"),
                jwt.getClaimAsStringList("roles"));
    }
}


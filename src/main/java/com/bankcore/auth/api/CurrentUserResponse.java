package com.bankcore.auth.api;

import java.util.List;
import java.util.UUID;

import org.springframework.security.oauth2.jwt.Jwt;

import com.bankcore.shared.web.JwtClaims;

public record CurrentUserResponse(UUID id, String email, List<String> roles) {

    public CurrentUserResponse {
        roles = List.copyOf(roles);
    }

    public static CurrentUserResponse from(Jwt jwt) {
        return new CurrentUserResponse(
                JwtClaims.subject(jwt),
                jwt.getClaimAsString("email"),
                jwt.getClaimAsStringList("roles"));
    }
}


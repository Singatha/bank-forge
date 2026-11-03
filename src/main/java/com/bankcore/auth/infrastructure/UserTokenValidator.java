package com.bankcore.auth.infrastructure;

import java.util.UUID;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

@Component
public class UserTokenValidator implements OAuth2TokenValidator<Jwt> {

    private static final OAuth2Error INVALID_USER = new OAuth2Error(
            "invalid_token", "The token no longer represents an active user session.", null);

    private final UserRepository userRepository;

    public UserTokenValidator(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        UUID userId;
        try {
            userId = UUID.fromString(jwt.getSubject());
        } catch (RuntimeException exception) {
            return OAuth2TokenValidatorResult.failure(INVALID_USER);
        }

        Number claimedVersion = jwt.getClaim("token_version");
        if (claimedVersion == null) {
            return OAuth2TokenValidatorResult.failure(INVALID_USER);
        }

        return userRepository.findById(userId)
                .filter(user -> user.isActive() && user.getTokenVersion() == claimedVersion.intValue())
                .map(user -> OAuth2TokenValidatorResult.success())
                .orElseGet(() -> OAuth2TokenValidatorResult.failure(INVALID_USER));
    }
}


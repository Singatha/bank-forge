package com.bankcore.auth.application;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bankcore.auth.domain.AuthException;
import com.bankcore.auth.domain.RefreshToken;
import com.bankcore.auth.domain.Role;
import com.bankcore.auth.domain.RoleName;
import com.bankcore.auth.domain.User;
import com.bankcore.auth.infrastructure.JwtService;
import com.bankcore.auth.infrastructure.JwtService.IssuedAccessToken;
import com.bankcore.auth.infrastructure.OpaqueTokenService;
import com.bankcore.auth.infrastructure.RefreshTokenRepository;
import com.bankcore.auth.infrastructure.RoleRepository;
import com.bankcore.auth.infrastructure.UserRepository;
import com.bankcore.config.SecurityProperties;

@Service
public class AuthService {

    private static final String DUMMY_PASSWORD_HASH =
            "$2a$10$C6UzMDM.H6dfI/f/IKcEe.1WiBm7.KX5b5p1jYQ3Yh6n2aH5lV5e.";

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final OpaqueTokenService opaqueTokenService;
    private final JwtService jwtService;
    private final SecurityProperties securityProperties;
    private final Clock clock;

    public AuthService(UserRepository userRepository,
                       RoleRepository roleRepository,
                       RefreshTokenRepository refreshTokenRepository,
                       PasswordEncoder passwordEncoder,
                       OpaqueTokenService opaqueTokenService,
                       JwtService jwtService,
                       SecurityProperties securityProperties,
                       Clock clock) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.opaqueTokenService = opaqueTokenService;
        this.jwtService = jwtService;
        this.securityProperties = securityProperties;
        this.clock = clock;
    }

    @Transactional
    public User register(String email, String password) {
        String normalizedEmail = normalizeEmail(email);
        validatePasswordBytes(password);
        if (userRepository.existsByEmail(normalizedEmail)) {
            throw AuthException.emailAlreadyRegistered();
        }

        Role customerRole = roleRepository.findByName(RoleName.CUSTOMER)
                .orElseThrow(() -> new IllegalStateException("CUSTOMER role seed is missing"));
        Instant now = Instant.now(clock);
        User user = new User(UUID.randomUUID(), normalizedEmail, passwordEncoder.encode(password), customerRole, now);
        return userRepository.saveAndFlush(user);
    }

    @Transactional
    public TokenPair login(String email, String password) {
        String normalizedEmail = normalizeEmail(email);
        validatePasswordBytes(password);
        User user = userRepository.findByEmail(normalizedEmail).orElse(null);
        if (user == null) {
            passwordEncoder.matches(password, DUMMY_PASSWORD_HASH);
            throw AuthException.invalidCredentials();
        }
        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            throw AuthException.invalidCredentials();
        }
        if (!user.isActive()) {
            throw AuthException.accountDisabled();
        }
        return issueTokenPair(user, UUID.randomUUID());
    }

    @Transactional(noRollbackFor = AuthException.class)
    public TokenPair refresh(String rawRefreshToken) {
        String tokenHash = opaqueTokenService.hash(rawRefreshToken);
        RefreshToken currentToken = refreshTokenRepository.findByTokenHashForUpdate(tokenHash)
                .orElseThrow(AuthException::invalidRefreshToken);
        Instant now = Instant.now(clock);

        if (currentToken.isRevoked()) {
            refreshTokenRepository.revokeFamily(currentToken.getFamilyId(), now);
            throw AuthException.refreshTokenReused();
        }
        if (currentToken.isExpired(now)) {
            refreshTokenRepository.revokeFamily(currentToken.getFamilyId(), now);
            throw AuthException.invalidRefreshToken();
        }
        if (!currentToken.getUser().isActive()) {
            refreshTokenRepository.revokeFamily(currentToken.getFamilyId(), now);
            throw AuthException.accountDisabled();
        }

        String replacementValue = opaqueTokenService.generate();
        RefreshToken replacement = new RefreshToken(
                UUID.randomUUID(),
                currentToken.getUser(),
                currentToken.getFamilyId(),
                opaqueTokenService.hash(replacementValue),
                now.plus(securityProperties.refreshTokenTtl()),
                now);
        refreshTokenRepository.save(replacement);
        currentToken.rotateTo(replacement.getId(), now);

        IssuedAccessToken accessToken = jwtService.issue(currentToken.getUser());
        return new TokenPair(accessToken.value(), accessToken.expiresAt(), replacementValue);
    }

    @Transactional
    public void logout(String rawRefreshToken) {
        String tokenHash = opaqueTokenService.hash(rawRefreshToken);
        refreshTokenRepository.findByTokenHashForUpdate(tokenHash)
                .ifPresent(token -> refreshTokenRepository.revokeFamily(
                        token.getFamilyId(), Instant.now(clock)));
    }

    private TokenPair issueTokenPair(User user, UUID familyId) {
        Instant now = Instant.now(clock);
        String refreshTokenValue = opaqueTokenService.generate();
        RefreshToken refreshToken = new RefreshToken(
                UUID.randomUUID(),
                user,
                familyId,
                opaqueTokenService.hash(refreshTokenValue),
                now.plus(securityProperties.refreshTokenTtl()),
                now);
        refreshTokenRepository.save(refreshToken);
        IssuedAccessToken accessToken = jwtService.issue(user);
        return new TokenPair(accessToken.value(), accessToken.expiresAt(), refreshTokenValue);
    }

    private String normalizeEmail(String email) {
        return email.strip().toLowerCase(Locale.ROOT);
    }

    private void validatePasswordBytes(String password) {
        if (password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw AuthException.invalidPasswordLength();
        }
    }
}


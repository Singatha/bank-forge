package com.bankcore.auth.api;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import com.bankcore.auth.domain.Role;
import com.bankcore.auth.domain.User;

public record UserResponse(UUID id, String email, List<String> roles, Instant createdAt) {

    public static UserResponse from(User user) {
        List<String> roles = user.getRoles().stream()
                .map(Role::getName)
                .sorted(Comparator.comparing(Enum::name))
                .map(Enum::name)
                .toList();
        return new UserResponse(user.getId(), user.getEmail(), roles, user.getCreatedAt());
    }
}


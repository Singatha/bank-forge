package com.bankcore.account.application;

import java.util.Set;
import java.util.UUID;

public record AccountActor(UUID userId, Set<String> roles) {

    public AccountActor {
        roles = Set.copyOf(roles);
    }

    public boolean hasRole(String role) {
        return roles.contains(role);
    }

    public boolean isStaff() {
        return hasRole("SUPPORT") || hasRole("ADMIN");
    }
}

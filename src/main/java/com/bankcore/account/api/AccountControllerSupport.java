package com.bankcore.account.api;

import org.springframework.security.oauth2.jwt.Jwt;

import com.bankcore.account.application.AccountActor;
import com.bankcore.shared.web.JwtClaims;

final class AccountControllerSupport {

    private AccountControllerSupport() {
    }

    static AccountActor actor(Jwt jwt) {
        return new AccountActor(JwtClaims.subject(jwt), JwtClaims.roles(jwt));
    }
}

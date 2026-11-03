# ADR-002: Use short-lived JWT access tokens and rotating opaque refresh tokens

- Status: Accepted
- Date: 2026-11-03

## Context

BankCore needs stateless API authorization without making long-lived bearer credentials difficult to revoke. It also needs session renewal, logout, role-based authorization, and a way to contain a stolen refresh token. The system is currently a single deployable with PostgreSQL as its source of truth.

## Decision

Issue HS256 JWT access tokens with a 15-minute default lifetime. Claims include the user ID as subject, normalized email, roles, issuer, issue and expiry timestamps, a unique token ID, and the user's token version. Resource-server validation checks the signature, issuer, time claims, active user status, and current token version.

Issue independent 256-bit opaque refresh tokens with a 30-day default lifetime. Persist only SHA-256 token hashes. Each login starts a token family. A refresh locks the matching row, revokes it, links it to a newly generated replacement, and returns the replacement exactly once. Presenting a revoked token is treated as reuse and revokes every active token in that family.

Logout is idempotent and revokes the supplied token's family. It does not maintain an access-token denylist; access tokens expire naturally, while disabling a user or incrementing the token version invalidates them during validation.

## Consequences

- Normal API authorization does not require server-side access-token storage.
- Refresh-token compromise is bounded by rotation and family-wide reuse detection.
- User status and token-version checks make access-token validation database-backed rather than completely stateless.
- HS256 requires every token issuer and verifier to protect the same secret. A future multi-service deployment should reassess asymmetric signing and key rotation.
- Logout cannot instantly revoke an individual access token without changing the design; the short access-token lifetime limits that window.

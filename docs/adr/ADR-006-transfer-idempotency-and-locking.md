# ADR-006: Serialize idempotent transfers and lock accounts deterministically

- Status: Accepted
- Date: 2026-11-11

## Context

Clients retry requests when responses are lost, and two transfer requests can reach the same account concurrently. A retry must not move funds twice, while competing transfers must not both validate against a stale balance and overdraw the account. The solution must preserve the existing atomic boundary across transfer state, balances, ledger entries, and audit records.

## Decision

Require an `Idempotency-Key` on `POST /api/v1/transfers`. Keys are scoped to the authenticated user and persisted with a SHA-256 fingerprint of the canonical business request. Reusing the same key and fingerprint returns the original completed transfer or original business failure. Reusing it with different input returns `IDEMPOTENCY_KEY_REUSED`.

Before reading an idempotency record, acquire a PostgreSQL transaction-scoped advisory lock derived from the user ID and key. Concurrent duplicates therefore serialize before the uniqueness check: the first request executes, and the second observes its committed outcome. The idempotency record is written in the transfer transaction. Infrastructure failure rolls it back so a safe retry can execute, while expected business failure commits the failed transfer and replayable error together.

Before validating funds, load the source and destination accounts with pessimistic write locks ordered by account UUID. Every transfer touching either account therefore sees the latest committed balance. Consistent ordering prevents opposite-direction transfers from acquiring the same pair in conflicting order.

The existing optimistic `version` column remains useful as a general stale-write guard, but transfer correctness relies on deliberate row locking rather than retries after optimistic conflicts.

## Consequences

- Normal and simultaneous retries execute a transfer at most once.
- A key cannot silently represent two different business requests.
- Concurrent withdrawals serialize and cannot overdraw an account.
- Lock scope lasts only for the existing transfer transaction.
- Transfers sharing an account trade some throughput for deterministic correctness.
- Advisory locking makes this implementation intentionally PostgreSQL-specific.
- Idempotency records can later receive an explicit retention policy without changing transfer semantics.

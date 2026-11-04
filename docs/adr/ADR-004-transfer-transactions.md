# ADR-004: Execute balance transfers in one PostgreSQL transaction

- Status: Accepted
- Date: 2026-11-04

## Context

A simulated transfer must never commit a source debit without the destination credit and corresponding transfer state. Expected business failures should be explainable in transfer history, while infrastructure failures must not leave partial state. Ledger and outbox records are introduced in later milestones.

## Decision

Use one Spring transaction for transfer creation, account validation, source debit, destination credit, and transfer completion. The transfer aggregate starts `PENDING`, moves to `PROCESSING`, and then reaches `COMPLETED` or `FAILED`. Domain methods reject every transition outside the declared state machine.

Account ownership and account existence are checked before creating a transfer record. After the record enters processing, expected account validation failures are converted to transfer business exceptions. Those exceptions commit the transfer as `FAILED` with a stable reason code while no balance mutation has occurred. Unexpected persistence or infrastructure failures roll back the complete transaction.

Accounts retain JPA optimistic versions. A conflicting update therefore cannot silently overwrite another balance, but friendly conflict handling, retry policy, deterministic locking, and concurrent overdraft tests are deferred to the dedicated reliability milestone.

## Consequences

- A completed transfer and both balance changes are atomic.
- Expected failures remain visible for support and customer history.
- Database failures remove the pending transfer and restore both account balances.
- This design currently updates stored balances directly; Milestone 5 will add balanced append-only ledger entries within the same boundary.
- Concurrent conflicts may currently surface as a generic request error instead of being retried or translated to a specialized concurrency error.

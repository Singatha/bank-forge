# ADR-001: Start BankCore as a modular monolith

- Status: Accepted
- Date: 2026-09-08

## Context

BankCore needs strong consistency for transfers and a clear way to learn domain boundaries. Distributing the initial system would add network failure modes and operational work before those boundaries have been validated.

## Decision

Build one Spring Boot deployable organized by business feature. Each feature will evolve toward `api`, `application`, `domain`, and `infrastructure` subpackages only when it contains behavior that needs those layers. Cross-cutting HTTP contracts and framework configuration live in `shared` and `config`.

PostgreSQL is the source of truth. Future asynchronous integration will use a transactional outbox rather than splitting core transactional writes across services.

## Consequences

- Transfers and ledger writes can share a local database transaction.
- Feature ownership remains explicit without the operational cost of microservices.
- Package boundaries require discipline because the JVM does not provide process isolation.
- A feature can be extracted later if evidence justifies the additional complexity.


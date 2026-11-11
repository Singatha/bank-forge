# ADR-007: Propagate request context and persist append-only audit records

- Status: Accepted
- Date: 2026-11-11

## Context

Production-style diagnosis requires one request to be traceable across application logs and durable business/security records. Request identifiers alone do not group retries or a future multi-service workflow, and ordinary application logs are not an appropriate authoritative audit store.

## Decision

Accept or generate UUID `X-Request-ID` and `X-Correlation-ID` headers. When no correlation ID is supplied, use the request ID. Return both headers and place both values in SLF4J MDC for the duration of request processing. Console logs use a stable key-value format containing timestamp, level, service, request ID, correlation ID, logger, and message. Application events log identifiers and stable reason codes, never authorization headers, passwords, or token values.

Persist immutable `audit_logs` for login success/failure, account creation, and transfer completion/failure. Records contain actor, action, resource, request ID, correlation ID, timestamp, and controlled JSON metadata. Successful login, account, and transfer audits participate in their business transaction. Login-failure audits use an independent transaction because the rejected authentication transaction must not erase its own security record.

Hibernate treats audit rows as immutable and a PostgreSQL trigger rejects every update and delete.

## Consequences

- Operators can correlate HTTP completion logs, application events, and audit rows.
- Authentication failures remain auditable even though authentication is rejected.
- Financial audit records cannot commit without their associated business outcome.
- Audit metadata is deliberately small and excludes request bodies and secrets.
- Logs are operational diagnostics; PostgreSQL audit rows are the durable record.
- Cross-process trace propagation can reuse the correlation ID when Kafka is introduced.

# ADR-008: Publish transfer events with a transactional outbox

- Status: Accepted
- Date: 2026-11-12

## Context

A transfer outcome and its integration event must not diverge. Publishing directly to Kafka inside the transfer transaction creates an unsafe dual write: PostgreSQL may commit after Kafka fails, or Kafka may accept an event before PostgreSQL rolls back. Kafka can also redeliver records, so consumers cannot assume exactly-once delivery.

## Decision

Write a `TRANSFER_COMPLETED` or `TRANSFER_FAILED` outbox row in the same PostgreSQL transaction as the transfer, balances, ledger, idempotency result, and audit record. The event envelope contains an event ID, event type, aggregate type and ID, occurrence time, correlation ID, and a controlled payload. Kafka records use the transfer ID as their key so events for one transfer remain ordered within a partition.

A scheduled publisher claims committed rows in small batches with `FOR UPDATE SKIP LOCKED`. Claim, Kafka publication, and final status updates use separate transactions so database locks are never held while waiting for Kafka. Rows move through `PENDING`, `PROCESSING`, `PUBLISHED`, or `FAILED`. Publication failures are retried with bounded backoff; stale `PROCESSING` rows are reclaimable after a timeout.

This design provides at-least-once delivery. A process can fail after Kafka accepts a record but before PostgreSQL records `PUBLISHED`, so publishing the same event again is expected. The notification consumer atomically inserts a consumer/event marker with PostgreSQL `ON CONFLICT DO NOTHING`; only the transaction that inserts the marker creates notification rows. A consumer failure rolls back both the marker and notifications.

The Kafka listener retries a failed record twice with a fixed delay, then publishes it to a partition-aligned dead-letter topic. The DLT retains the original key and body for inspection or controlled replay. Messaging components are enabled explicitly with `KAFKA_ENABLED=true`; transfer transactions always create outbox rows so enabling the publisher later does not lose events.

## Consequences

- Transfer outcomes and their outbox events commit or roll back together.
- Kafka outages do not extend the financial transaction or corrupt transfer state.
- Multiple publisher instances can claim different rows without double-processing the same claim.
- Publisher crashes can cause duplicates, and consumer idempotency makes those duplicates harmless.
- Poison events stop blocking their partition after bounded retries and remain available in the DLT.
- Notifications are currently an internal projection; an API can be added in a later milestone without changing event delivery.
- Operational cleanup and replay policies for published, failed, and dead-letter events remain future production-engineering work.

# ADR-009: Use Redis for distributed rate limiting and Prometheus for metrics

- Status: Accepted
- Date: 2026-11-12

## Context

Authentication and transfer writes need abuse controls that behave consistently across application instances. An in-memory counter would reset on restart and produce a separate quota on each instance. Operational diagnosis also needs application metrics, scrapeable health signals, and a useful local dashboard without turning metric dimensions into unbounded user or request data.

Redis is appropriate for short-lived coordination data but must not become another source of truth for financial state. PostgreSQL remains authoritative for identities, accounts, balances, transfers, ledger entries, audits, outbox events, and consumer state.

## Decision

Use Redis only for fixed-window rate-limit counters. A Lua script atomically increments a counter, sets its expiry on first use, and returns the count and remaining lifetime. Keys contain a bounded policy name and a SHA-256 digest of the identifier rather than a raw address or user ID. Authentication writes share a remote-address policy; transfer creation uses the authenticated JWT subject. Forwarded client-address headers are not trusted because BankCore has no configured trusted-proxy boundary.

Rate limiting is opt-in with `RATE_LIMITING_ENABLED=true`. When enabled, an unavailable Redis service returns `503 RATE_LIMIT_UNAVAILABLE`; requests are never silently admitted without the configured protection. Exceeded quotas return `429 RATE_LIMIT_EXCEEDED` with limit, remaining, reset, and retry headers.

Use Micrometer's Prometheus registry for standard runtime metrics and a small set of BankCore counters and timers. Tags are limited to controlled outcome and policy values; user IDs, account IDs, transfer IDs, paths containing identifiers, request IDs, and correlation IDs are prohibited as metric tags. Prometheus scrapes the Actuator endpoint and Grafana is provisioned with a Prometheus data source and an overview dashboard.

Keep liveness independent of external systems. Readiness includes PostgreSQL and conditionally registered Redis and Kafka indicators, so an enabled dependency can remove the instance from service while a disabled optional feature does not.

## Consequences

- Quotas are shared across instances and survive application restarts for the remainder of their short window.
- Fixed windows are understandable and inexpensive, but can allow bursts around a window boundary.
- Redis failure blocks protected writes only when rate limiting is enabled.
- Direct remote addresses are safe from spoofed forwarding headers, but a trusted reverse proxy requires an explicit deployment policy to recover the original client address.
- Low-cardinality metrics remain affordable and aggregatable, while request-level investigation stays in logs and audit data.
- The unauthenticated Prometheus endpoint supports the local Compose scraper and must be restricted by network policy in deployed environments.
- Grafana provisioning makes the local dashboard reproducible; alert routing and production retention remain deployment concerns.

# ADR-003: Enforce account ownership in application services and gate simulated funding

- Status: Accepted
- Date: 2026-11-04

## Context

JWT authentication establishes who sent a request, but it does not prove that the caller owns a requested account. BankCore also needs a way to seed balances before transfers exist, without presenting that mechanism as a real deposit or payment capability.

## Decision

Account application services authorize every account read against the authenticated user ID and JWT roles. Customers may read only accounts connected to their own customer profile. Unauthorized customer lookups return the same not-found response as missing accounts to avoid resource enumeration. `SUPPORT` and `ADMIN` receive explicit read access. Development funding permits the owner or `ADMIN`, while `SUPPORT` remains read-only.

The funding controller exists only when `bankcore.features.development-funding-enabled=true`; the default is false. Funding accepts positive amounts with at most two decimal places, requires the account currency, and rejects frozen or closed accounts. It locks the account row before changing the balance so concurrent development credits cannot overwrite one another.

Accounts carry a JPA version and PostgreSQL version column in preparation for later transfer concurrency controls. This milestone does not choose the final transfer locking strategy.

## Consequences

- Authentication remains separate from resource authorization.
- Customer account identifiers cannot be used to read another customer's data.
- Staff access is explicit and testable rather than implied by authentication.
- The seed mechanism is convenient locally but absent unless deliberately enabled.
- Funding changes balances directly because the ledger is introduced in a later milestone; the endpoint must remain development-only and will be reassessed when ledger-backed money movement exists.

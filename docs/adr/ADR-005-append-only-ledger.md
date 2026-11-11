# ADR-005: Record completed transfers in an append-only balanced ledger

- Status: Accepted
- Date: 2026-11-11

## Context

Account balances are convenient current-state projections, but they do not explain how funds moved. BankCore needs an immutable financial history where every completed transfer has equal debit and credit postings. Application validation alone is insufficient because direct SQL, later features, or a defect could otherwise commit a completed transfer with incomplete ledger data.

## Decision

Represent each completed transfer with exactly two `ledger_entries`: a `DEBIT` for the source account and a `CREDIT` for the destination account. Each entry stores its own positive `numeric(19,2)` amount, currency, account, transfer, and creation time. The application builds both entries through `LedgerPosting`, which rejects any pair with unequal amounts or currencies, duplicate entry types, different transfer IDs, or the same account on both sides.

The ledger writer uses mandatory transaction propagation. It can only run inside the transfer transaction, so the transfer state, both account balances, and both ledger entries commit or roll back together.

PostgreSQL is the final invariant boundary. Unique and check constraints limit each transfer to one entry of each type, distinct accounts, supported currencies, and positive amounts. Deferred constraint triggers reconcile the final state at commit: a completed transfer must have exactly one source debit and one destination credit whose amount and currency match the transfer, while a non-completed transfer must have no entries. Deferral permits the two rows and transfer state to be assembled in any flush order within one transaction without exposing a partially balanced committed state.

Ledger rows are marked immutable in Hibernate and contain no mutation methods. A database trigger rejects every `UPDATE` and `DELETE`, including changes attempted outside the application.

`GET /api/v1/accounts/{accountId}/transactions` is derived from ledger entries joined with descriptive transfer data. It uses the existing owner/staff account authorization rules. Failed transfers are not financial transactions and therefore do not appear. The opt-in development funding helper also remains outside the ledger because it is test setup rather than a production deposit flow.

## Consequences

- Completed transfers have an auditable, balanced source of transaction history.
- A ledger write failure rolls back the transfer and both balance changes.
- Database writes cannot silently create, alter, or remove an unbalanced posting.
- Account transaction history contains committed financial postings, not mutable balance snapshots or failed attempts.
- The `(account_id, created_at desc)` index supports newest-first account history; transfer-level uniqueness indexes also support reconciliation lookups.
- Reversals will require an explicit compensating-entry design before a reversal workflow is exposed; existing ledger rows will never be edited.
- Pagination is intentionally deferred to Milestone 6 with the rest of the reliability work.

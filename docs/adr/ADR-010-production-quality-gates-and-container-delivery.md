# ADR-010: Enforce production quality gates and container delivery

- Status: Accepted
- Date: 2026-11-12

## Context

BankCore now exercises PostgreSQL, Kafka, and Redis behavior and includes operational monitoring. A successful local compile is no longer enough to protect its cross-service contracts. Pull requests need reproducible tests, enforceable static and coverage checks, dependency-risk visibility, and proof that the deployable container can be built. Performance regressions also need a small repeatable baseline without making every pull request depend on noisy timing measurements.

Dependency vulnerability data and performance measurements have different availability and stability characteristics from deterministic compilation and tests. Combining every control into one mandatory job would make normal feedback dependent on an external vulnerability feed and shared-runner timing.

## Decision

Use `mvn verify` as the deterministic pull-request quality gate. It runs unit and Testcontainers tests, Checkstyle, SpotBugs, and a JaCoCo bundle line-coverage check with a 60% minimum. Keep Checkstyle rules concise and repository-owned. Suppress SpotBugs findings only for explicitly named framework-managed dependency-injection classes and the documented JPA constructor constraint; do not use package-wide exclusions.

Run GitHub dependency review on pull requests. Run OWASP Dependency-Check in a separate scheduled and manually invocable workflow, with an optional NVD API key supplied through repository secrets. This keeps vulnerability intelligence visible without making the main verification path depend on an initial NVD database update.

Build the Java 21 application with a multi-stage Dockerfile and run it in a smaller JRE image under an unprivileged user. Pull requests build the image without publishing it. Registry publishing, signing, and environment promotion require a later deployment decision.

Keep k6 performance checks in a scheduled and manually invocable workflow. The smoke scenario registers and authenticates a synthetic user, exercises health and identity reads, and fails when request errors reach 1%, checks fall to 99% or lower, p95 reaches 500 ms, or p99 reaches one second. These are regression thresholds for the Compose topology, not capacity claims for a production platform.

Maintain the architecture overview and ADRs in the repository so structural decisions change with the code and pass through the same review process.

## Consequences

- Pull requests receive one repeatable signal for behavior, style, common defects, and minimum coverage.
- The 60% coverage floor establishes a ratchetable baseline but does not imply that all important behavior is tested; integration assertions and invariant coverage remain more important than the percentage alone.
- Testcontainers gives realistic dependency behavior at the cost of requiring Docker and increasing verification time.
- Scheduled dependency scanning can report new risk without a source change, while its external feed can fail independently of the primary build.
- Narrow static-analysis exclusions are reviewable but must be revisited when constructor injection or persistence mapping changes.
- A non-root image reduces runtime privilege, but secure deployment still requires hardened infrastructure, external secrets, network policy, image signing, and patch operations.
- k6 thresholds catch large regressions in the local topology but are sensitive to runner capacity and do not replace dedicated load, soak, or failure testing.

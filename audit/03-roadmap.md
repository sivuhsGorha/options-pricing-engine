# Prioritised remediation roadmap

## Fix this week

| Priority | Work | Findings | Acceptance evidence |
|---:|---|---|---|
| P0 | Rotate/revoke every committed provider key; remove defaults; use a secret store and redact query strings from logs. Enforce HTTPS. | AUD-01 | Repository/history scan shows no active credential; integration smoke test uses injected secret. |
| P0 | Disable public serving until authn/authz, TLS, safe static-root resolution, restrictive CORS, method checks, response headers, request limits, and WebSocket origin/session controls are implemented. | AUD-02, AUD-03, AUD-04 | Authenticated integration tests show unauthenticated requests fail; traversal corpus returns no file bytes. |
| P0 | Correct `VectorPdeSolver` argument order; remove the false SIMD claim or implement actual vectorized kernels. Add a numerical regression grid against a trusted oracle. | AUD-05 | Absolute/relative tolerances across call/put, rates, vols, expiries, dividends; no-arbitrage properties pass. |
| P0 | Replace the zero-volatility limit with discounted deterministic payoff and reject impossible implied-vol inputs before root finding. | AUD-06, AUD-07 | Put-call parity, lower/upper bounds, monotonicity and IV rejection tests pass. |
| P0 | Make routing fail closed: validate all finite/positive fields and limits atomically; remove simulated success from execution interfaces; do not call it an exchange gateway or kill switch. | AUD-08 | Negative, zero, overflow, NaN/infinite, concurrent and rate-limit tests all reject safely; exchange acknowledgement is required in any production adapter. |

## Fix this month

| Priority | Work | Findings | Acceptance evidence |
|---:|---|---|---|
| P1 | Design an authoritative market-data, instrument, order, fill, position and risk-state model; persist append-only audit records and introduce a validated contract master. | AUD-09, AUD-13 | Reconciliation/restart test recovers exact positions and order state. |
| P1 | Replace mmap multi-field writes with a versioned seqlock/double-buffer snapshot; bound queues and provide backpressure/overflow policy. Close all arenas and remove allocations from claimed hot paths. | AUD-10, AUD-11 | Stress test detects no torn snapshots, leaks, or unbounded latency. |
| P1 | Implement official or independently validated margin/risk models, model conventions, calibration constraints, and reference test data. Rename approximations until then. | AUD-12, AUD-13 | Independent quant sign-off plus daily golden-data regression. |
| P1 | Create a reproducible Java build (Maven/Gradle), pin supported JDK/toolchains, repair Docker, add CI, SBOM, dependency audit and image scan. | AUD-14, AUD-15 | Clean checkout builds/tests/container-starts in CI. |

## Fix this quarter

| Priority | Work | Findings | Acceptance evidence |
|---:|---|---|---|
| P2 | Replace per-connection threads and full-file reads with bounded executors, connection lifecycle handling, timeouts, metrics and load tests. | AUD-04, AUD-11 | 10×/100× load profile meets defined p99/error budget. |
| P2 | Add structured logging, tracing, readiness/liveness, alerting, immutable config and deployment/rollback runbooks. | AUD-16 | Game-day recovery and alert tests completed. |
| P2 | Separate research/demo UI from operational UI; make labels reflect source freshness and model provenance, then perform keyboard/mobile/accessibility testing. | AUD-17 | UX acceptance and accessibility checks against target standard. |

## Do not bother with yet

- **[VERIFIED]** Do not optimize `FastMath`, JIT warmup, SIMD branding, or microsecond latency while the vector path creates arrays/records/PDE grids and fails correctness equivalence (AUD-05, AUD-15).
- **[INFERRED]** Do not pursue exchange certification, kernel bypass, or co-location work before order state, risk controls, authenticated operations, and independent model validation exist.
- **[VERIFIED]** Do not add a database merely to persist the current random dashboard state. First define business entities, source-of-truth ownership, reconciliation, retention, and recovery requirements.

# Running and Deploying (INFRA.md)

How the application is built, configured, run and checked. Section 5 lists tuning ideas that are *not* part
of the shipped deployment and have not been measured.

---

## 1. Build and run

```bash
mvn clean verify                 # compile, 336 tests, JaCoCo gate (70%), package jar + target/lib
java --add-modules jdk.incubator.vector -jar target/options-pricing-engine-1.0.0-SNAPSHOT.jar
```

Requirements: JDK 25, Maven 3.9+. Node 22 only to rebuild the frontend (`cd web-react && npm ci && npm run
build`, output goes to `web/`). `run_all.ps1` runs the Java verify, the Python tests, and the frontend lint
and build, and exits non-zero on the first failure.

**Docker** (`Dockerfile`): three stages, Node 22 builds the frontend, Maven builds the jar, a JRE 25 Alpine
image runs it as a non-root user with `-XX:MaxRAMPercentage=75.0`. The container exposes 8080 and 8081, has
a health check on `/`, and keeps its state under `/app/data`. CI builds the image and confirms an
unauthenticated `/api/health` returns 401. `docker-compose.yml` runs it with your `.env`, publishes the ports
to `127.0.0.1` only, and keeps `/app/data` (fill ledger, mmap state) in a named volume across restarts.

**Shutdown.** Ctrl+C (SIGTERM) runs `AppCompositionRoot.close()`: the HTTP and WebSocket servers stop first,
then the surface calibration thread, then the engine, which closes the mmap state. Each step is guarded so
one failure does not skip the rest.

**Health.** `GET /api/health` (authenticated) reports an overall `status` (`ok`, `degraded`, `down`) and one
entry per component: market data (status, age, source), engine risk state (readable or not), surface
calibration (state, message, source, demo flag) and trading (halted, reason, strategy switch), plus the last
known status of each market-data provider read without probing them. See [RUNBOOK.md](RUNBOOK.md).

---

## 2. Configuration

Non-secret settings live in `config.yaml` (copy `config.example.yaml`; the file is git-ignored). The parser
is a minimal YAML reader: `section:` headers, two-space indentation, `key: value` scalars (numbers, `true`/
`false`, quoted or bare strings), inline lists `[a, "b"]`, and `#` comments on their own line or after a value.
Tabs, block lists (`- item`), lines without a key and duplicate keys are errors that name the line; a value of
the wrong type is an error that names the key and the value. Check a configuration without starting anything:

```bash
java -jar target/options-pricing-engine-1.0.0-SNAPSHOT.jar --check-config
```

It reads `config.yaml` and the environment, lists every problem at once (never printing a secret), says
which market-data keys are set, and exits 0 when valid, 1 otherwise. A normal start with a bad configuration
prints the same messages and exits 2 instead of a stack trace.

Secrets and deployment settings come from the environment or a `.env` file in the working directory
(`config/EnvironmentConfigLoader`); `.env` is git-ignored and must never be committed.

| Variable | Required | Meaning |
| :--- | :--- | :--- |
| `API_SECRET` | yes, >= 32 chars | HMAC key for signed API requests |
| `OPERATOR_PASSWORD` | yes, >= 12 chars | dashboard login |
| `PORT` | no (8080) | HTTP port; WebSocket uses `PORT + 1` |
| `BIND_ADDRESS` | no (127.0.0.1) | set `0.0.0.0` only behind a TLS-terminating proxy |
| `ALLOWED_ORIGIN` | no | comma-separated origins for CORS and WebSocket; defaults to localhost on `PORT` |
| `TRUSTED_PROXIES` | no | proxy addresses whose `X-Forwarded-For` is believed |
| `COOKIE_SECURE` | no | force the `Secure` cookie flag when TLS terminates upstream |
| `HTTP_REQUEST_TIMEOUT_SECONDS` | no | per-request timeout for the dashboard server |
| `MMAP_STATE_FILE` | no (`data/shm_state.dat`) | engine-to-web shared state file |
| `DATA_DIR` | no (`data`) | fill ledger (`fills.csv`) and other state that must survive a restart |
| `ALLOW_SIMULATED_DATA` | no (false) | lets SIMULATED quotes back paper orders |
| `FINNHUB_KEY`, `POLYGON_API_KEY`, `ALPHA_VANTAGE_KEY`, `MARKETSTACK_KEY` | at least one for live data | spot providers |
| `FRED_API_KEY` | no | enables the US Treasury par-yield curve |

---

## 3. Process layout

One JVM. `Main` builds `AppCompositionRoot` (config, engine, position tracker, risk, order manager, strategy
loop, harness, dashboard), runs the demo pricing and backtest, starts the harness (engine ticks every 10 ms,
feed status probe on a daemon thread) and the dashboard (JDK HTTP server plus a Jetty WebSocket server).

**Shared state**: the engine writes net Greeks and the scenario margin to a 56-byte memory-mapped file under a
seqlock (`core/MmapStatePublisher`); the web layer reads it (`web/MmapStateReader`) and returns 503 while it is
unavailable rather than reporting zero risk. The file is created owner-only (plus SYSTEM and Administrators on
Windows). The two sides can be split into separate processes without code changes to the format.

**Security** (`web/OptionsDashboardServer`): sessions in secure, same-site cookies with a cap on concurrent
sessions; HMAC request signing with replay protection; CSP and other security headers; login rate limiting;
credential redaction in logs; client address resolution that trusts `X-Forwarded-For` only from
`TRUSTED_PROXIES`.

---

## 4. CI

`.github/workflows/ci.yml` on every push and pull request: `mvn -B clean verify` (tests + JaCoCo gate),
Python script tests, Docker build and readiness check, frontend `npm ci && npm run lint && npm test &&
npm run build`, and a Gitleaks secret scan (`.gitleaks.toml`). The JaCoCo minimum is ratcheted upward only.

---

## 5. Tuning ideas (not implemented, not measured)

These are standard low-latency Linux and JVM practices. Nothing in this repository applies or verifies them,
and the application has no measured latency figures, so none of them can be justified yet.

- CPU isolation (`isolcpus`, `nohz_full`, `rcu_nocbs`), performance governor, IRQ affinity, huge pages,
  swap off. `scripts/setup_os_tuning.sh` sets the governor and prints the rest as commented examples.
- Kernel bypass (Solarflare OpenOnload / EF_VI). Pointless without an exchange connection.
- JVM: ZGC, `-XX:+AlwaysPreTouch`, large pages, `JitCompilerWarmer` at startup (exists, nothing calls it).
- Monitoring: Prometheus via JMX exporter, Grafana, alerting. None is wired up.

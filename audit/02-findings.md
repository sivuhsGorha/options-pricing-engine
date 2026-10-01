# Findings

## Verification record

| Command | Observed result |
|---|---|
| `javac --add-modules jdk.incubator.vector -d target/classes <all src/main/java files>` | Exit 0; one incubator-module warning (JDK 26.0.1). |
| `java --add-modules jdk.incubator.vector -cp target/classes com.sbk.optionspricer.Main` | Exit 0. All executable demo suites printed success; the output also reported vector pricing `89,868.30 us` vs scalar `877.10 us` for 1,024 strikes. |
| Temporary external audit probe run with `java --add-modules jdk.incubator.vector -cp target/classes C:\tmp\AuditProbe.java` | Zero-vol call/put parity `10.0000` vs expected `14.3894`; vector PDE `26.3143` vs BSM `16.6994`; impossible IV price returned `5.0`. Temporary probe was removed; no project source was changed. |
| `npm.cmd run lint` in `web-react` | Exit 0; three unused catch-parameter warnings in `src/App.jsx`. |
| `npm.cmd audit --package-lock-only --json` | Timed out after 63.6 seconds; no vulnerability result. Initial `npm` alias failed because local PowerShell policy blocks `npm.ps1`. |

## Findings index

| ID | Title | Dimension | Severity | Likelihood | Effort | Evidence location |
|---|---|---|---|---|---|---|
| AUD-01 | Provider credentials are committed and one provider uses plaintext HTTP | Security | High | High | S | `fetch_real_api_data.py:7-10,55` |
| AUD-02 | Dashboard/API has no authentication or authorization | Security | High | High | M | `OptionsDashboardServer.java:41-175` |
| AUD-03 | Static handler permits traversal outside `web` | Security | High | High | S | `OptionsDashboardServer.java:184-207` |
| AUD-04 | WebSocket can exhaust threads/sockets and streams unauthenticated risk data | Security/Reliability | High | High | M | `WebSocketDashboardServer.java:41-44,80-118` |
| AUD-05 | Vector PDE swaps rate and volatility | Correctness | Critical | High | S | `VectorPdeSolver.java:45-49` |
| AUD-06 | Zero-volatility pricing violates discounted deterministic payoff and parity | Correctness | Critical | Medium | S | `BlackScholesPricer.java:24-27,82-89` |
| AUD-07 | Implied-volatility solver accepts impossible prices | Correctness | High | High | S | `ImpliedVolatilitySolver.java:66-86` |
| AUD-08 | Pre-trade validation allows invalid orders; route success is simulated | Execution/Risk | High | High | M | `PreTradeRiskFilter.java:30-57`; `SmartOrderRouter.java:37-55` |
| AUD-09 | Market-data decoder lacks packet validation and failure containment | Correctness/Reliability | High | Medium | M | `EobiDecoder.java:34-60`; `HistoricalReplayEngine.java:38-64` |
| AUD-10 | Unified engine silently suppresses errors and has unbounded off-heap lifetime | Reliability/Performance | High | High | M | `UnifiedQuantEngine.java:77-119` |
| AUD-11 | Mmap risk snapshot can be torn and dashboard invents initial risk data | Reliability/Data integrity | High | Medium | M | `MmapStatePublisher.java:43-47`; `MmapStateReader.java:31-45` |
| AUD-12 | Surface/SLV and margin labels exceed implemented validation/model scope | Correctness/Risk | High | Medium | L | `SsviCalibrator.java:17-30,70-87`; `SlvCalibrator.java:38-80`; `SpanMarginSimulator.java:31-57` |
| AUD-13 | Portfolio/risk state has no validation, concurrency control, persistence, or reconciliation | Data/Risk | High | Medium | L | `PortfolioPosition.java:19-46`; `GreekAggregator.java:12-24` |
| AUD-14 | Docker runtime is not reproducibly buildable | Operations | High | High | M | `Dockerfile:8-25` |
| AUD-15 | Tests and benchmarks do not prove claimed correctness or latency | Quality | High | High | M | `Level3UpgradesTest.java:105-117`; `InstitutionalSuiteTest.java:89-92` |
| AUD-16 | Historical VaR and routing arithmetic have uncontrolled invalid/edge inputs | Correctness | Medium | Medium | S | `HistoricalVaRCalculator.java:17-31`; `gateways/SmartOrderRouter.java:42-65` |
| AUD-17 | UI presents simulated/stale data as live operational information | UI/UX | Medium | High | M | `App.jsx:110-115,221-235`; `OptionsDashboardServer.java:48-66` |

## Detailed findings

### AUD-01 — Provider credentials are committed and one provider uses plaintext HTTP

**[VERIFIED] Severity High · Likelihood High · Effort S.** Any repository reader, fork, artifact scanner, or log containing the URL can use the exposed provider credentials. MarketStack traffic can be observed or altered in transit.

Evidence, [`fetch_real_api_data.py:7-10,55`](../fetch_real_api_data.py):

```python
POLYGON_API_KEY = os.getenv("POLYGON_API_KEY", "CtBWgC3o...")
FINNHUB_KEY = os.getenv("FINNHUB_KEY", "dav2239...")
MARKETSTACK_KEY = os.getenv("MARKETSTACK_KEY", "6d438...")
url = f"http://api.marketstack.com/v1/eod/latest?access_key={MARKETSTACK_KEY}&symbols={symbol}"
```

Rotate/revoke every shown value immediately, remove source defaults and history, use an injected secret manager value, require HTTPS, and avoid putting credentials in query strings where a header is supported. Add secret scanning in pre-commit/CI.

### AUD-02 — Dashboard/API has no authentication or authorization

**[VERIFIED] Severity High · Likelihood High · Effort M.** An attacker able to reach the server can retrieve risk state and surface information. There is no user identity, role, ownership check, CSRF model, or rate limiting. Wildcard CORS lets any browser origin read the endpoints.

Evidence, [`OptionsDashboardServer.java:41-46,75-80`](../src/main/java/com/sbk/optionspricer/web/OptionsDashboardServer.java):

```java
server.createContext("/", new StaticFileHandler());
server.createContext("/api/spot", (exchange -> {
    exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
...
server.createContext("/api/risk", (exchange -> {
    exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
    double delta = mmapReader.getNetDelta();
```

Put the service behind TLS and an authenticated reverse proxy before exposing it. Enforce a server-side identity/role authorization decision per endpoint and WebSocket session; allow only configured trusted origins and apply request/connection quotas.

### AUD-03 — Static handler permits traversal outside `web`

**[VERIFIED] Severity High · Likelihood High · Effort S.** Request paths are concatenated with `web` and never normalized/canonicalized or checked to remain under that root. A path such as `/../README.md` resolves outside the static root on filesystems that accept it, enabling project-file disclosure.

Evidence, [`OptionsDashboardServer.java:184-205`](../src/main/java/com/sbk/optionspricer/web/OptionsDashboardServer.java):

```java
String path = exchange.getRequestURI().getPath();
if (path.equals("/")) path = "/index.html";
File file = new File("web" + path);
if (file.exists()) {
    byte[] bytes = Files.readAllBytes(file.toPath());
```

Resolve the requested path against `web.toRealPath()`, reject malformed/encoded traversal, and require `candidate.startsWith(root)` before opening. Serve a fixed asset manifest through an established web server where possible. Add traversal tests for encoded and separator variants.

### AUD-04 — WebSocket can exhaust threads/sockets and streams unauthenticated risk data

**[VERIFIED] Severity High · Likelihood High · Effort M.** Each accepted socket creates a native thread with no connection limit, handshake timeout, Origin validation, authentication, frame parsing, close lifecycle, or client read loop. Slow/non-reading peers can hold resources while the broadcast thread blocks on writes.

Evidence, [`WebSocketDashboardServer.java:41-44,72-73,103-107`](../src/main/java/com/sbk/optionspricer/web/WebSocketDashboardServer.java):

```java
Socket clientSocket = serverSocket.accept();
new Thread(() -> handleHandshake(clientSocket), "ws-client-" + clientSocket.getPort()).start();
...
activeClients.add(socket);
...
out.write(rawFrame);
out.flush();
```

Use a bounded event-loop/server library or bounded executor, socket/read/write/idle timeouts, authenticated upgrades, Origin allowlisting, max client/message/frame limits, and nonblocking backpressure. Publish only a versioned snapshot authorized for that client.

### AUD-05 — Vector PDE swaps rate and volatility

**[VERIFIED] Severity Critical · Likelihood High · Effort S.** The public method documents `(timeToExpiry, rate, vol)`, but constructs `OptionParameters` as `(timeToExpiry, vol, rate)`. It prices the wrong economic inputs. The audit probe measured `26.3142627126` versus BSM `16.6994464491` for S=100, K=90, T=1, r=5%, vol=20%.

Evidence, [`VectorPdeSolver.java:30-32,45-49`](../src/main/java/com/sbk/optionspricer/models/pde/VectorPdeSolver.java):

```java
public static void priceBatchPdeVectorized(... double timeToExpiry, double rate, double vol, ...)
...
OptionParameters params = new OptionParameters(spot, tempK[lane], timeToExpiry, vol, rate, 0.0);
tempPrices[lane] = DiscreteDividendPricer.price(..., params, null, 150, 150, true);
```

Pass `rate, vol` in declared order and add a regression comparing this API with a correctly parameterized PDE/reference grid. Block release until call/put bounds, parity (European/no discrete dividends), and convergence tests pass.

### AUD-06 — Zero-volatility pricing violates discounted deterministic payoff and parity

**[VERIFIED] Severity Critical · Likelihood Medium · Effort S.** At positive maturity and zero volatility, price is not spot intrinsic value: it is `max(S exp(-qT) - K exp(-rT), 0)` for a call and the analogous put. The code returns immediate intrinsic. The audit probe found C−P=10.0 while required parity is 14.3893517949 for S=100, K=90, T=1, r=5%, q=0.

Evidence, [`BlackScholesPricer.java:24-27,82-89`](../src/main/java/com/sbk/optionspricer/BlackScholesPricer.java):

```java
if (isDegenerate(timeToExpiry, volatility)) {
    return intrinsicValue(type, spot, strike);
}
...
return (type == OptionType.CALL) ? Math.max(spot - strike, 0.0)
                                 : Math.max(strike - spot, 0.0);
```

Split expiry from zero-volatility handling. Use discounted deterministic payoff for `T > 0 && vol ≈ 0`, define ATM delta convention explicitly, and add no-arbitrage property tests across rates/dividends.

### AUD-07 — Implied-volatility solver accepts impossible prices

**[VERIFIED] Severity High · Likelihood High · Effort S.** Bisection never verifies that market price lies between attainable prices at its volatility bounds. For a call priced at 200 with S=K=100, T=1, r=5%, the probe returned the arbitrary upper bound 5.0 instead of rejection.

Evidence, [`ImpliedVolatilitySolver.java:66-86`](../src/main/java/com/sbk/optionspricer/ImpliedVolatilitySolver.java):

```java
double lo = VOL_LOWER_BOUND;
double hi = VOL_UPPER_BOUND;
double priceLo = ... - marketPrice;
...
return (lo + hi) / 2.0;
```

Validate all finite inputs and call/put discounted no-arbitrage lower/upper bounds first. Confirm opposite signs at bracket endpoints; return a typed failure (`Optional`/result code) if no solution exists. Never present a capped endpoint as implied volatility.

### AUD-08 — Pre-trade validation allows invalid orders; route success is simulated

**[VERIFIED] Severity High · Likelihood High · Effort M.** Negative quantities/prices, zero values, `NaN`, infinity, invalid IDs and integer overflow are not rejected. A negative quantity produces negative notional and passes both `>` checks. Filter state is not synchronized. After passing, the router discards the payload yet returns `true`.

Evidence, [`PreTradeRiskFilter.java:30-45`](../src/main/java/com/sbk/optionspricer/execution/PreTradeRiskFilter.java) and [`SmartOrderRouter.java:37-50`](../src/main/java/com/sbk/optionspricer/execution/SmartOrderRouter.java):

```java
if (order.quantity() > maxOrderQuantity) return false;
double notional = order.quantity() * order.price();
if (notional > maxOrderNotional) return false;
...
byte[] networkPayload = new byte[18];
...
transmitToExchange(networkPayload);
return true;
```

Use fixed-point ticks/notional or checked decimal domain values; reject all non-finite/non-positive inputs before limits; atomically reserve limits and sequence/order IDs; require a transport acknowledgement and durable lifecycle state. Keep mocks behind test interfaces only.

### AUD-09 — Market-data decoder lacks packet validation and failure containment

**[VERIFIED] Severity High · Likelihood Medium · Effort M.** `packet[0]` and later ByteBuffer reads occur with no null/minimum-length/schema/sequence check; malformed input can throw after `claim()` and before `commit()`. Replay uses `split(",")` rather than a CSV parser and assumes local rows are valid. A full ring spins indefinitely by design.

Evidence, [`EobiDecoder.java:34-60`](../src/main/java/com/sbk/optionspricer/gateways/EobiDecoder.java):

```java
if (packet[0] != 1) return;
...
OrderBookTick tick = ringBuffer.claim();
tick.setTimestamp(buffer.getLong(1));
...
ringBuffer.commit();
```

Validate packet length/type/version before claiming; handle decoder failures with counters and deterministic recovery; define sequence-gap/duplicate policy; use a real CSV parser or prohibit quoted fields. Add malformed/fuzz, ring-full and producer-failure tests.

### AUD-10 — Unified engine silently suppresses errors and has unbounded off-heap lifetime

**[VERIFIED] Severity High · Likelihood High · Effort M.** Every tick allocates from a shared arena but never releases individual allocations; the loop creates arrays/objects and calls a PDE. All exceptions are discarded, so state can freeze while logs still claim the engine is online. It also uses random synthetic spot/delta rather than gateway data.

Evidence, [`UnifiedQuantEngine.java:77-94,114-119`](../src/main/java/com/sbk/optionspricer/core/UnifiedQuantEngine.java):

```java
MemorySegment tickSegment = MemorySegmentStructs.allocateTick(arena);
currentSpot += (Math.random() - 0.5) * 0.10;
double[] strikes = new double[]{90.0, 95.0, 100.0, 105.0, 110.0};
...
} catch (Exception e) {
    // Protect loop execution
}
```

Preallocate bounded buffers or use a per-cycle confined arena; fail closed on model/data failure, expose health/metrics and alerting; remove synthetic production state. Make `start/stop` ownership close the ring buffer and await scheduler termination.

### AUD-11 — Mmap risk snapshot can be torn and dashboard invents initial risk data

**[VERIFIED] Severity High · Likelihood Medium · Effort M.** Four independent stores have no version/fence/snapshot protocol, so readers may combine fields from different updates. If no engine state exists, the reader writes hard-coded risk values, which dashboard labels as connected/live state.

Evidence, [`MmapStatePublisher.java:43-47`](../src/main/java/com/sbk/optionspricer/core/MmapStatePublisher.java) and [`MmapStateReader.java:31-45`](../src/main/java/com/sbk/optionspricer/web/MmapStateReader.java):

```java
mappedSegment.set(ValueLayout.JAVA_DOUBLE, 0, netDelta);
mappedSegment.set(ValueLayout.JAVA_DOUBLE, 8, netGamma);
...
raf.writeDouble(-62500.0);  // netDelta
...
this.mappedSegment = channel.map(FileChannel.MapMode.READ_ONLY, 0, FILE_SIZE, arena);
```

Use a versioned seqlock or double-buffer record with release/acquire semantics and a validity/timestamp flag. A missing/stale publisher must produce an explicit unavailable state, never plausible defaults. Add concurrent tear/staleness tests.

### AUD-12 — Surface/SLV and margin labels exceed implemented validation/model scope

**[VERIFIED] Severity High · Likelihood Medium · Effort L.** SSVI validates only three local parameter ranges and checks butterfly condition at one k; it cannot establish calendar/no-static-arbitrage over a surface. SLV uses simplified derivatives and clamps a computed leverage to 5.0; the included test reports local vol `289.3445` but passes because it only rejects NaN. “SPAN/Prisma” is four fixed Greek corners, not an exchange parameter file/calculation.

Evidence, [`SsviCalibrator.java:22-25,70-87`](../src/main/java/com/sbk/optionspricer/volatility/SsviCalibrator.java), [`SlvCalibrator.java:59-80`](../src/main/java/com/sbk/optionspricer/volatility/SlvCalibrator.java), [`SpanMarginSimulator.java:31-57`](../src/main/java/com/sbk/optionspricer/risk/SpanMarginSimulator.java):

```java
if (eta <= 0) throw ...;
if (gamma <= 0 || gamma > 0.5) throw ...;
...
return gK >= 0.0;
...
return Math.max(0.2, Math.min(leverage, 5.0));
...
double[][] scenarios = {{ .15, .20 }, { .15, -.20 }, { -.15, .20 }, { -.15, -.20 }};
```

Rename demonstrations as approximations until independently validated. Enforce surface constraints across strikes/maturities, calibrate to vetted data with residual diagnostics, and use official clearing-risk parameter files/adapters for margin.

### AUD-13 — Portfolio/risk state lacks validation, concurrency control, persistence, and reconciliation

**[VERIFIED] Severity High · Likelihood Medium · Effort L.** Positions accept null symbols, arbitrary multipliers, non-finite Greeks and unbounded int arithmetic. An unsynchronized `ArrayList` is concurrently mutable in a system described as real-time. No fills/order records persist, so restart/reconciliation is impossible.

Evidence, [`PortfolioPosition.java:19-46`](../src/main/java/com/sbk/optionspricer/risk/PortfolioPosition.java) and [`GreekAggregator.java:12-24`](../src/main/java/com/sbk/optionspricer/risk/GreekAggregator.java):

```java
this.symbol = symbol;
this.quantity = quantity;
this.multiplier = multiplier;
...
private final List<PortfolioPosition> positions = new ArrayList<>();
public void addPosition(PortfolioPosition position) { positions.add(position); }
```

Introduce an authoritative append-only fill/position ledger, instrument master and validated domain units; define a single-writer/concurrent snapshot strategy; reconcile against venue/clearing reports and persist risk calculation provenance.

### AUD-14 — Docker runtime is not reproducibly buildable

**[VERIFIED] Severity High · Likelihood High · Effort M.** The Docker build compiles only selected folders, omits root, execution, gateway, rates and PDE packages required by imports, and does not add the incubator Vector module. It declares JDK 21 while local success used JDK 26. It then runs a server needing an mmap/CSV state not copied or orchestrated.

Evidence, [`Dockerfile:8-25`](../Dockerfile):

```dockerfile
COPY src/ /app/src/
COPY web/ /app/web/
RUN ... javac -d target/classes \
    src/main/java/com/sbk/optionspricer/core/*.java \
    src/main/java/com/sbk/optionspricer/web/*.java
CMD ["java", "-cp", "target/classes", "com.sbk.optionspricer.web.OptionsDashboardServer"]
```

Adopt Maven/Gradle with one pinned toolchain, reproduce exactly in multi-stage Docker, compile all source/dependencies, add runtime flags, and test container startup/readiness in CI. Decide whether the core engine is a sidecar or separate service; do not hide a missing publisher with default risk values.

### AUD-15 — Tests and benchmarks do not prove claimed correctness or latency

**[VERIFIED] Severity High · Likelihood High · Effort M.** Tests are executable print demos, not a test framework. Key checks only test positive prices or a broad 1e-2 difference. The vector batch uses lane arrays and scalar math; suite output showed it about 100× slower than scalar for 1,024 strikes, yet prints success. There are no boundary/no-arbitrage/failure/security/concurrency tests.

Evidence, [`Level3UpgradesTest.java:105-117`](../src/main/java/com/sbk/optionspricer/benchmark/Level3UpgradesTest.java) and [`InstitutionalSuiteTest.java:83-92`](../src/main/java/com/sbk/optionspricer/benchmark/InstitutionalSuiteTest.java):

```java
maxDiff = Math.max(maxDiff, Math.abs(pricesSimd[i] - pricesScalar[i]));
if (maxDiff > 1e-2) throw new AssertionError(...);
System.out.println("-> SIMD Vector pricing PASSED.");
...
if (prices[0] <= 0) throw new AssertionError(...);
```

Use JUnit + property tests and trusted reference cases; require parity, bounds, monotonicity, convergence and calibration residuals. Use JMH with warmup/forks/blackholes for latency; publish reproducible machine/JDK/flags and fail regression thresholds.

### AUD-16 — Historical VaR and routing arithmetic have uncontrolled edge inputs

**[VERIFIED] Severity Medium · Likelihood Medium · Effort S.** VaR returns `abs(sortedPnL[index])`, so an all-profitable sample reports a positive loss. It has no minimum sample rule, quantile convention, missing-data policy, liquidity horizon or stressed model. Venue routing accepts wrong weight length, zero/negative/NaN weights and potentially creates negative/incorrect allocations.

Evidence, [`HistoricalVaRCalculator.java:17-31`](../src/main/java/com/sbk/optionspricer/risk/HistoricalVaRCalculator.java) and [`gateways/SmartOrderRouter.java:42-62`](../src/main/java/com/sbk/optionspricer/gateways/SmartOrderRouter.java):

```java
int index99 = (int) Math.floor(sortedPnL.length * 0.01);
return Math.abs(sortedPnL[index99]);
...
for (double w : venueLiquidityWeights) totalWeight += w;
int qty = ... Math.round(totalQuantity * (venueLiquidityWeights[i] / totalWeight));
```

Define VaR loss-sign/quantile conventions and sample controls; return zero only where economically correct. Validate order quantity and exactly one finite non-negative weight per venue, positive total weight, and allocation conservation.

### AUD-17 — UI presents simulated/stale data as live operational information

**[VERIFIED] Severity Medium · Likelihood High · Effort M.** React generates random latency and tape values. The server calls static CSV-derived strike a “live fetched SPY quote” and returns a fixed provider/status string even on CSV failure. This can mislead users during an outage or operational decision.

Evidence, [`App.jsx:110-115,221-235`](../web-react/src/App.jsx) and [`OptionsDashboardServer.java:48-66`](../src/main/java/com/sbk/optionspricer/web/OptionsDashboardServer.java):

```javascript
setLatency(Math.floor(Math.random() * 3 + 4));
const tapeInterval = setInterval(generateLiveTrade, 300);
```

```java
double spotPrice = 762.63;
"activeProvider": "Finnhub API (Live Rate Feed)",
"status": "CONNECTED"
```

Separate demo from operational UI. Bind displays to timestamped, attributable source data and show degraded/unavailable status. Add keyboard focus labels, responsive overflow behaviour, error state UI, and accessibility checks before user-facing release.

## Areas done well

- **[VERIFIED]** The non-degenerate Black-Scholes call/put formula includes `exp(-qT)` and `exp(-rT)` consistently ([`BlackScholesPricer.java:29-38`](../src/main/java/com/sbk/optionspricer/BlackScholesPricer.java)).
- **[VERIFIED]** The primary `OptionParameters` constructor rejects negative expiry/volatility and non-positive spot/strike ([`OptionParameters.java:21-26`](../src/main/java/com/sbk/optionspricer/OptionParameters.java)).
- **[VERIFIED]** Local build is simple and did compile all Java files cleanly, apart from the expected incubator warning.

## Review limitations

The audit did not perform external exchange certification, provider credential validation, live-network penetration testing, package vulnerability lookups, container build, production load testing, or model validation against independent market data. Those items are marked [UNKNOWN] where relevant rather than assumed.

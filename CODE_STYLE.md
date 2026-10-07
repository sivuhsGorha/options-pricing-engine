# Code Style (CODE_STYLE.md)

Conventions for the Java, Python and JavaScript in this repository. The rules below exist because each one
has already caught a real defect here.

---

## 1. Correctness rules

1. **Never fabricate a value.** A missing quote is `UNAVAILABLE`, not 100.0; a failed implied-volatility
   inversion returns empty, not 20%; an unreadable risk state is a 503, not zeros. Placeholders that remain
   (volume 2000, +/- 1 cent spreads) are documented in DATA.md as defects.
2. **Label simulations.** A class that simulates a feed, venue or protocol says `SIMULATION` in its header and
   in its log lines. Documentation says what is implemented and what is not, in separate sections.
3. **One convention per concept.** Dates become times only through `TimeConventions` (ACT/365F). Prices
   become wire ticks only through `PriceScale` (HALF_EVEN at 4 decimals). Notional is always
   `quantity x price x multiplier`.
4. **Fail loudly on impossible state.** A fill larger than the order, a booking failure or a negative tree
   probability throws or trips the trading halt. Do not clamp, floor or "repair" and continue.
5. **Secrets come from the environment** (`EnvironmentConfigLoader`, `.env`). Never in code, config files,
   tests or logs; log messages redact credentials and never print request URLs with keys.

---

## 2. Numerical rules

- Check `Double.isFinite` at boundaries (T -> 0, sigma -> 0, S -> 0, deep in/out of the money) and reject or
  return empty rather than produce NaN.
- Compare floating-point values with a tolerance that is justified in a comment (and in the test).
- Document the accuracy of every approximation with a measured bound (see `FastMath`), and do not use a
  low-precision approximation where the value feeds a price or a risk number.
- Prefer formulations that keep relative accuracy in tails (erfc, not 1 - Phi; log-space grids for the PDE).
- Convergence criteria are on the quantity being solved for (volatility step), not on a proxy (price error).

---

## 3. Performance rules

Hot paths are the engine tick, ring-buffer producer and consumer, the PDE and tree inner loops. In them:

- Avoid allocation: reuse pre-allocated primitive arrays, pass primitives, no boxing, no streams or lambdas.
- Do not add synchronisation without a reason; where it is needed (seqlock writer, order manager) it is explicit.
- Measure before claiming. No latency or throughput figure goes into documentation without a committed
  benchmark that produced it.

Outside hot paths, prefer clarity: records for parameter groups, `Optional` for absent results, small classes.

---

## 4. Tests

- Tests live under `src/test` and are committed with the change they cover. CI enforces a JaCoCo coverage
  minimum that only ratchets upward; never lower it, disable a test, or loosen an assertion to get a build green.
- Write the test first and watch it fail against the old code where feasible; a test that never failed has
  not proven anything.
- A test named for behaviour (`producerCannotOverwriteATickTheConsumerIsStillReading`) is preferred to one named
  for a method.
- Tests must be hermetic: no network, no real API keys, no dependence on the clock beyond injected instants.
  Older tests that construct `LiveSpotProvider` directly are being migrated.
- Numerical tests state the reference they compare against and why its accuracy exceeds the tolerance.

---

## 5. Naming and documentation

- Java: PascalCase classes, camelCase members, UPPER_SNAKE_CASE constants. Python: PEP 8. JavaScript: the
  oxlint configuration in `web-react/`, with warnings treated as errors.
- Every mathematical method's Javadoc states the formula, assumptions and edge-case behaviour. Where a method
  replaces a defective earlier version, the Javadoc says what was wrong, so the reason is not lost.
- Commit messages say what changed and why in the body, in plain language.
- Line endings: files are committed with LF; Git normalises on Windows checkouts.

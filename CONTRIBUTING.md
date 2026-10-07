# Developer Guidelines & Contribution Workflow (CONTRIBUTING.md)

Thank you for contributing to the Options Pricing & Quantitative Trading Platform. Correctness comes first: changes to pricing or risk code need tests that show the numbers are right.

---

## 1. Development Principles

1. **Empirical Verification First**: Every claim regarding performance improvement or numerical accuracy must be accompanied by benchmark logs and empirical test suites.
2. **Avoid Allocation on Hot Paths**: Keep market data loops and option pricing routines allocation-free where practical, and do not add allocations to them without a reason.
3. **Absence of Arbitrage Constraints**: Any modification to pricing, interpolation, or volatility surface code must prove mathematically and empirically that no static or dynamic arbitrage opportunities are introduced.

---

## 2. Pull Request Workflow

```
1. Fork / Branch (feature/short-description or fix/short-description)
2. Implement Code & Unit Tests (tests required for every change; CI enforces a JaCoCo coverage minimum that only ratchets upward)
3. Run Local Benchmarks & Micro-suite Verification
4. Submit PR with Detailed Quantitative Justification
5. Automated CI Checks & Peer Code Review
6. Merge to Main
```

---

## 3. Mandatory Quantitative Verification Checks

Before submitting a Pull Request, you **MUST** run and pass all quantitative sanity checks:

```bash
# Compile, run the full test suite and enforce the coverage gate (the same command CI runs)
mvn -B clean verify
```

### Pull Request Checklist:
- [ ] `mvn clean verify` passes on JDK 25.
- [ ] Closed-form pricing matches Monte Carlo simulation within 95% Confidence Interval.
- [ ] Put-Call parity condition $C - P = S e^{-qT} - K e^{-rT}$ holds to within $10^{-6}$ tolerance.
- [ ] Implied Volatility solver accurately recovers input volatility across extreme strikes ($0.5 \le K/S \le 1.5$) and short maturities ($T = 1 \text{ day}$).
- [ ] No allocations added to critical pricing loops.

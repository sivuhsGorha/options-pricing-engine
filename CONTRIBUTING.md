# Developer Guidelines & Contribution Workflow (CONTRIBUTING.md)

Thank you for contributing to the Options Pricing & Quantitative Trading Platform. This project operates under strict institutional quality, performance, and correctness standards.

---

## 1. Development Principles

1. **Empirical Verification First**: Every claim regarding performance improvement or numerical accuracy must be accompanied by benchmark logs and empirical test suites.
2. **Zero Allocation on Hot Paths**: Code executing in market data loops, option pricing routines, or execution gateways must perform zero memory allocations on the Java heap.
3. **Absence of Arbitrage Constraints**: Any modification to pricing, interpolation, or volatility surface code must prove mathematically and empirically that no static or dynamic arbitrage opportunities are introduced.

---

## 2. Pull Request Workflow

```
1. Fork / Branch (feature/short-description or fix/short-description)
2. Implement Code & Unit Tests (100% test coverage required on core pricing math)
3. Run Local Benchmarks & Micro-suite Verification
4. Submit PR with Detailed Quantitative Justification
5. Automated CI Checks & Peer Code Review
6. Merge to Main
```

---

## 3. Mandatory Quantitative Verification Checks

Before submitting a Pull Request, you **MUST** run and pass all quantitative sanity checks:

```bash
# Compile and run full test & verification suite
javac -d target/classes src/main/java/com/sbk/optionspricer/*.java
java -cp target/classes com.sbk.optionspricer.Main
```

### Pull Request Checklist:
- [ ] Code compiles cleanly on JDK 21 LTS with zero warnings.
- [ ] Closed-form pricing matches Monte Carlo simulation within 95% Confidence Interval.
- [ ] Put-Call parity condition $C - P = S e^{-qT} - K e^{-rT}$ holds to within $10^{-6}$ tolerance.
- [ ] Implied Volatility solver accurately recovers input volatility across extreme strikes ($0.5 \le K/S \le 1.5$) and short maturities ($T = 1 \text{ day}$).
- [ ] Zero memory allocations added to critical pricing loops.

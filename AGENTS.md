# Autonomous AI Quant Agent Operating Manual (AGENTS.md)

This document establishes the operational rules, system constraints, verification requirements, and safety protocol bounds for autonomous AI agents working within this repository.

---

## 1. Agent Operating Persona & Rules

1. **Role & Identity**: You operate as a Senior Quantitative Engineering Grandmaster. You hold expertise in low-latency systems development (Java 21/C++), stochastic options calculus, quantitative risk management, and high-frequency exchange connectivity.
2. **Empirical Verification Standard**: **NEVER** claim a pricing fix, algorithm optimization, or refactoring is complete without running verification commands and observing clean zero-error outputs.
3. **Absence of Arbitrage Principle**: Never introduce changes that violate Put-Call Parity ($C - P = S e^{-qT} - K e^{-rT}$) or produce negative option prices, negative volatilities, or NaN outputs.

---

## 2. Mandatory Workflow Checklist for Agents

```
┌───────────────────────────────────────────────────────────────────────────────────────────┐
│ 1. Read Knowledge Items & Task Specifications                                             │
└──────────────────────────────┬────────────────────────────────────────────────────────────┘
                               │
                               ▼
┌───────────────────────────────────────────────────────────────────────────────────────────┐
│ 2. Inspect Existing Codebase & Verify Signatures (Never infer missing symbols)             │
└──────────────────────────────┬────────────────────────────────────────────────────────────┘
                               │
                               ▼
┌───────────────────────────────────────────────────────────────────────────────────────────┐
│ 3. Execute Code Edits with Zero-Allocation Compliance                                      │
└──────────────────────────────┬────────────────────────────────────────────────────────────┘
                               │
                               ▼
┌───────────────────────────────────────────────────────────────────────────────────────────┐
│ 4. Run Build & Verification Test Suite via Terminal Tools                                  │
│    (mvn -B clean verify   — tests + JaCoCo gate; the same command CI runs)                 │
│    (cd web-react && npm run lint && npm run build   — when the frontend changed)           │
└──────────────────────────────┬────────────────────────────────────────────────────────────┘
                               │
                               ▼
┌───────────────────────────────────────────────────────────────────────────────────────────┐
│ 5. Synthesize Empirical Results and Provide File Links                                    │
└───────────────────────────────────────────────────────────────────────────────────────────┘
```

---

## 3. Subagent Specialization & Delegation Guidelines

When executing multi-faceted quantitative tasks, spawn specialized subagents:

- **Quant Math Agent**: Specialized in stochastic differential equations, Monte Carlo path generators, Heston/SABR numerical solvers, and yield curve bootstrapping.
- **Low-Latency Systems Agent**: Specialized in Java off-heap memory management (`MemorySegment`), LMAX Disruptor ring buffer integration, SIMD vector instructions, and Solarflare OpenOnload kernel bypass.
- **Exchange Connectivity Agent**: Specialized in Euronext Optiq, Eurex T7 ETI, LSEG SOLA binary protocols, ITCH/EOBI order book decoders, and FIX SBE message handlers.
- **Risk & Backtest Agent**: Specialized in event-driven simulation, market impact models, Eurex Prisma / SPAN margin engines, and real-time Greek limit monitors.

---

## 4. Safety & Destruction Safeguards

- **Accidental Data Loss Prevention**: **NEVER** run commands that perform irreversible deletion (`rm -rf`, `git reset --hard` on uncommitted work, `DROP DATABASE`) without explicit user permission.
- **Resource Attribution**: Ensure all command invocations follow proper environment constraints.
- **Log Extraction Rigor**: If a compilation command or unit test fails, immediately retrieve and inspect the un-truncated stdout/stderr logs before forming any fix hypothesis.
- **Tests Are Committed**: Tests under `src/test/` are part of the repository and CI verification. Commit them alongside the change they cover. **NEVER** delete, disable, or weaken a test just to make a build pass.

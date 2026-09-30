# Code Style Standards & Zero-Allocation Rules (CODE_STYLE.md)

This document dictates coding conventions, performance anti-patterns, and numerical precision rules for Java, C++, and Python codebase components.

---

## 1. Zero-Allocation Java Standards (Hot Path)

The hot execution path includes market data handlers, order book reconstruction, pricing/Greeks calculations, and execution message marshalling.

### 🚫 Forbidden on Hot Path:
- `new` object instantiations (e.g., `new Double()`, `new String()`, `new ArrayList()`).
- Auto-boxing / unboxing (`Double` instead of primitive `double`).
- Java Streams, Lambdas, or Iterator creation.
- String concatenation (`"Price: " + price`).
- Dynamic memory resizing or array allocation.

### ✅ Mandatory Patterns:
- Use **Java Records** for immutable parameter passing outside the hot path, and primitive arrays / off-heap `MemorySegment` buffers on the hot path.
- Pre-allocate scratch objects / buffers during initialization (in constructors or static setup).
- Use `final` primitive local variables (`double`, `long`, `int`).

```java
// BAD: Allocates objects on hot path
public Double calculateCallPrice(OptionParameters params) {
    List<Double> results = new ArrayList<>();
    results.add(params.spot() - params.strike());
    return results.get(0);
}

// GOOD: Zero allocation, primitive double on hot path
public static double calculateCallPrice(double spot, double strike, double timeToExpiry, double rate, double vol) {
    if (timeToExpiry <= 1e-10) {
        return Math.max(spot - strike, 0.0);
    }
    // Analytical calculations using primitives
    ...
}
```

---

## 2. Floating-Point & Numerical Precision Rules

1. **IEEE 754 Standards**: Always check for `Double.isNaN()` or `Double.isInfinite()` when operating near boundary conditions ($T \to 0$, $\sigma \to 0$, $S \to 0$).
2. **Epsilon Comparisons**: Never use exact binary equality `==` on floating-point numbers. Use absolute or relative tolerance:
   ```java
   public static final double PRICE_TOLERANCE = 1e-6;
   if (Math.abs(calculatedPrice - expectedPrice) < PRICE_TOLERANCE) { ... }
   ```
3. **Small Number Division**: Prevent division by near-zero values in derivative step calculations (e.g., Vega division in Newton-Raphson). Provide automatic fallback routines.

---

## 3. Class & Naming Conventions

- **Class Names**: PascalCase (e.g., `BlackScholesPricer`, `ImpliedVolatilitySolver`).
- **Method & Variable Names**: camelCase (e.g., `calculateGreeks`, `timeToExpiry`).
- **Constants**: UPPER_SNAKE_CASE (e.g., `NEAR_ZERO = 1e-10`, `MAX_ITERATIONS = 50`).
- **Math Symbol Mapping**: Variable names should include mathematical symbol references in comments (e.g., `double spot; // S`, `double strike; // K`).

---

## 4. Documentation & Docstrings

- Every mathematical function must explicitly state its formula, assumptions, and edge case behaviors in standard Javadoc format.
- Document maximum absolute error rates for rational polynomial approximations (e.g. Abramowitz & Stegun normal CDF error $\sim 1.5 \times 10^{-7}$).

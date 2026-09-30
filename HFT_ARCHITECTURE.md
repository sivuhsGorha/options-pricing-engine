# High-Frequency Trading (HFT) Architecture

This document dictates the architectural constraints required to achieve sub-microsecond latency in the SBK Options Pricing Engine.

## 1. Zero-Garbage Critical Path (Memory-Mapped IPC)
The core Java engine MUST NOT allocate objects (`new`) during the critical pricing loop.
* **Separation of Concerns:** The engine calculating the math must never handle HTTP requests, string formatting, or JSON serialization.
* **IPC via mmap:** The pricing engine writes its output directly to an off-heap memory-mapped file (using `java.lang.foreign.MemorySegment`).
* **Sidecar API:** A secondary process (Node.js, Rust, or a separate JVM) reads the memory-mapped file and handles all REST/WebSocket communication with the frontend.

## 2. Vectorized Fast-Math (SIMD)
Standard `java.lang.Math` functions drop to JNI `libm`, which defeats the C2 JIT compiler's ability to vectorize loops.
* **Chebyshev Polynomials:** We replace `Math.exp()` and `Math.log()` with custom 7th-order Chebyshev approximations.
* **Java 21 Vector API:** The innermost loops (e.g., Monte Carlo paths, Black-Scholes arrays) use 256-bit AVX2 vector instructions (`jdk.incubator.vector`) to process 4-8 doubles in a single CPU cycle.

## 3. Network Stack Optimization
* **Kernel Bypass:** The system is designed to run over Solarflare OpenOnload, bypassing the Linux TCP/IP stack entirely.
* **Thread Pinning:** Critical threads are pinned to isolated CPU cores using `isolcpus` to prevent OS context switching.

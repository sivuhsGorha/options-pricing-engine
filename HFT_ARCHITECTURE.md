# Low-Latency Design Notes

These are design intentions and constraints for the pricing core, not measured results. No latency
benchmark is published for this project; do not quote a figure until one is measured and committed.

## 1. Keeping garbage off the pricing path (memory-mapped IPC)
The pricing engine should avoid allocating objects in its inner loops.
* **Separation of concerns:** the engine publishes state; HTTP, string formatting and JSON live in the web layer.
* **IPC via mmap:** the engine writes risk state to a memory-mapped file (`MmapStatePublisher`, using
  `java.lang.foreign.MemorySegment`) guarded by a seqlock. `MmapStateReader` in the web layer reads it.
  The sidecar is a thread in the same JVM today; it can be split into a separate process.

## 2. Vectorised maths
* `SimdMath` and `VectorBlackScholesPricer` use the incubator Vector API (`jdk.incubator.vector`, enabled
  with `--add-modules jdk.incubator.vector`) for batch pricing.
* `FastMath` holds polynomial approximations; check their error bounds in the tests before relying on them.

## 3. Not implemented
* **Kernel bypass (Solarflare OpenOnload) and thread pinning** are deployment ideas only. Nothing in the
  code or `scripts/setup_os_tuning.sh` installs or verifies them, and no exchange connection exists to
  benefit from them.
* **LMAX Disruptor:** `MarketDataRingBuffer` is a single-producer single-consumer ring in the same spirit,
  not the Disruptor library.

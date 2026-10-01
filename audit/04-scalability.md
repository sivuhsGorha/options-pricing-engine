# Scalability and growth plan

## First bottlenecks

| Rank | Verified bottleneck | Why it breaks | Cheapest postponement |
|---:|---|---|---|
| 1 | `UnifiedQuantEngine` allocates a fresh shared-arena tick every 10 ms and never closes it; it also allocates arrays, parameter objects, and lists in the scheduled cycle. | Off-heap memory has no bounded lifetime, while garbage collection cannot reclaim arena allocations. At 100 Hz the tick allocation alone is at least 6.4 KB/s, excluding retained arena bookkeeping; work per tick includes PDE pricing. | Preallocate/reuse tick buffers; close or rotate bounded arenas; remove calculations from the I/O loop; measure allocation rate and p99. |
| 2 | The batch PDE method runs scalar 150×150 PDE solves per strike and allocates lane arrays and `OptionParameters`. | The included suite measured 16 “vectorized PDE” prices in 33,865 µs; the same code is not a vector kernel. | Route non-critical requests to cached/precomputed grids; use scalar verified model first; only vectorize after profile-guided benchmark. |
| 3 | Dashboard uses a 10-thread HTTP executor, full `Files.readAllLines` on `/api/spot`, full static-file reads, and unbounded thread-per-WebSocket client. | Concurrent clients can exhaust threads/heap/file descriptors; request latency is coupled to local disk and slow sockets. | Bound executors/connection count, cache data, add socket/read/write timeouts, stream/bound files, and apply reverse-proxy limits. |

## 10× and 100× outlook

| Area | 10× | 100× |
|---|---|---|
| Market data | Ring-buffer producer spin waits when consumers fall behind; malformed packets can kill the ingest call. | No sequence-gap/recovery policy; producer/consumer topology does not fan out safely. |
| Pricing/risk | CPU saturation and off-heap growth from the unified loop; snapshots may be internally inconsistent. | Model/risk calculation cannot be trusted or scaled horizontally because state is process-local/mmap local. |
| Dashboard | HTTP and WebSocket threads become a straightforward DoS vector. | Process becomes unstable; no load shedding, metrics, autoscaling, or multi-instance state coordination exists. |
| Operations | Manual working-directory files and local CSV/mmap paths prevent reproducible deployment. | There is no safe rollout, recovery, backup, or observability model. |

## Target growth sequence

1. Establish correct, deterministic models and a typed source-of-truth event stream before performance work.
2. Make market-data ingestion bounded and observable; define loss/backpressure policy and per-instrument sequence/recovery semantics.
3. Publish immutable, versioned risk snapshots to a durable/evented interface rather than four independently written mmap doubles.
4. Split public API/dashboard from computation, authenticate both, and operate them behind a TLS reverse proxy with quotas.
5. Set concrete SLOs (price error tolerance, accepted data age, tick-loss policy, p99 latency, availability) and load-test 10×/100× with production-like data.

There is no current connection pooling, distributed cache, database, queue service, telemetry, health check, backup or rollback implementation. [VERIFIED] Recommendations above are therefore a staged architecture direction, not a claim that these facilities already exist.

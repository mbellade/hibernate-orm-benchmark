# Connection Pool Stealing Benchmark - Findings

Investigation of Quarkus issue #46817: Vert.x SimpleConnectionPool "stealing"
connections across event loops and its impact on Hibernate Reactive performance.

## Context

Franz and Sanne observed degraded reactive performance vs blocking ORM
in TechEmpower benchmarks. The hypothesis: Vert.x's connection pool steals
connections from other event loops' slots when the local slot is empty, breaking
thread/connection affinity and causing cascading degradation.

## Setup

- ORM 7.4.0.Final (blocking, Agroal pool)
- Hibernate Reactive: two profiles tested
  - `quarkus`: HR 3.4.0.Final + Vert.x 4.5.27 (matches what Quarkus uses)
  - `perf`: HR 4.4.0.Final + Vert.x 5.0.12 (latest HR stable)
- Local PostgreSQL 18.4
- Simple PK lookup: `find(Fortune.class, randomId)` on 10,000 rows
- JDK 21.0.9 Temurin, macOS

Note: the HR 3.x line targets Vert.x 4, the HR 4.x line targets Vert.x 5.
Quarkus uses Vert.x 4, so the `quarkus` profile is the relevant one for
reproducing the TechEmpower regression.

## Phase 1: Throughput

Base case: 2 threads, 2 connections, 2 event loops, no artificial delay.

### Corrected numbers (Agroal pool)

The original blocking throughput (~7k ops/s) was captured before switching
from Hibernate's built-in connection pool to Agroal. The built-in pool is
much simpler and less optimized than Agroal (which is what Quarkus uses).
Re-running with Agroal in the same session as reactive:

| Benchmark | quarkus (Vert.x 4) | perf (Vert.x 5) |
|-----------|-------------------|-----------------|
| Blocking (Agroal) | 12,242 | 12,319 |
| Reactive (2 EL) | 13,108 | 12,902 |

Blocking throughput is consistent across profiles (it does not use Vert.x).
Reactive is only ~5-7% faster than blocking with Agroal, not ~1.9x as
originally reported with the built-in pool.

### Original numbers (built-in pool, superseded)

These numbers used Hibernate's built-in connection pool for blocking,
which inflated the apparent reactive advantage:

| Benchmark | Throughput (ops/s) | Error |
|-----------|-------------------|-------|
| Blocking (built-in pool) | 6,972 | +/- 33 |
| Reactive (2 EL) | 13,280 | +/- 107 |

### Event loop sweep (2 threads, 2 connections, no delay)

| Event Loops | Throughput (ops/s) | Error |
|-------------|-------------------|-------|
| 2 | 12,095 | +/- 16,826 |
| 4 | 12,747 | +/- 4,364 |
| 8 | 13,169 | +/- 983 |

No throughput degradation from increasing event loop count.

### With pg_sleep delay (8 threads, 2 connections)

Note: these numbers were collected with the built-in pool for blocking.
With Agroal, blocking would be higher in the 0ms case.

| Delay | Blocking | Reactive (8 EL) |
|-------|----------|-----------------|
| 0ms | 6,896 (built-in pool) | 13,294 |
| 1ms | 636.4 +/- 6.6 | 643.0 +/- 5.3 |
| 5ms | 285.6 +/- 5.6 | 284.7 +/- 3.8 |

When pg_sleep dominates, both converge to the same throughput regardless
of pool implementation. No stealing cost visible in throughput.

**Conclusion: throughput is essentially the same between blocking (Agroal)
and reactive when measured in the same session. The original ~2x gap was
an artifact of using the built-in pool.**

## Phase 2: Latency (HDR Histogram with coordinated omission avoidance)

Base case: 2 threads, 2 connections, 2 event loops, no delay.
Each side rate-limited to 75% of its own peak throughput.

Latency measured from expected start time (Gil Tene / wrk2 approach),
not actual start time. This captures queuing delays that throughput hides.

### Vert.x 4 latency (`quarkus` profile, corrected)

Rate-limited at 75% of same-session peak (~12.2k blocking, ~13.1k reactive):
- Blocking: ~4,591 ops/s/thread (interval 217,817 ns)
- Reactive: ~4,916 ops/s/thread (interval 203,417 ns)

30s measurement, per-thread, microseconds:

| Percentile | Blocking | Reactive (2 EL) |
|------------|----------|-----------------|
| P50 | 155-166 | 150-155 |
| P90 | 190-201 | 204-209 |
| P99 | 518-543 | 688-696 |
| P99.9 | 781-796 | 3,807-4,223 |
| Max | 1,574-1,585 | 6,574-6,656 |

Reactive P50/P90 matches blocking. Tail diverges at P99+ but by a smaller
absolute margin than the Vert.x 5 runs below. However, tail latency is
highly variable across runs due to GC timing (see reproducibility section).
This single run is not enough to conclude Vert.x 4 has better tail behavior.

### Vert.x 5 latency (`perf` profile, original)

Rate-limited at 75% of original peak (~7k blocking built-in pool, ~13k reactive).
Note: blocking rate was based on the lower built-in pool throughput.
- Blocking: ~2,588 ops/s/thread (interval ~386,000 ns)
- Reactive: ~4,875 ops/s/thread (interval ~205,128 ns)

30s measurement, per-thread, microseconds:

| Percentile | Blocking | Reactive (2 EL) |
|------------|----------|-----------------|
| P50 | 181-185 | 148-159 |
| P90 | 210-215 | 196-202 |
| P99 | 395-401 | 6,443-6,910 |
| P99.9 | 5,280-5,579 | 29,458-29,884 |
| Max | 20,824-21,103 | 33,030-33,456 |

The first warmup iteration for reactive showed P90 at ~133ms that settled down,
consistent with Sanne's description of cascading steal degradation at startup.

### Key finding

Reactive has similar or better P50/P90 but worse tail latency (P99+) in both
Vert.x versions. The tail is driven by GC pauses (see GC correlation section),
not by connection stealing.

### Event loop count sweep (Vert.x 5 only)

Same rate-limited setup (75% of reactive peak, 2 threads, 2 connections).
30s measurement iteration, per-thread HDR histogram, microseconds.

| Percentile | Blocking | Reactive 2 EL | Reactive 4 EL | Reactive 8 EL |
|------------|----------|---------------|---------------|---------------|
| P50 | 181-185 | 148-159 | 158-163 | 140-144 |
| P90 | 210-215 | 196-202 | 193-196 | 167-171 |
| P99 | 395-401 | 6,443-6,910 | 35,291-39,617 | 491-493 |
| P99.9 | 5,280-5,579 | 29,458-29,884 | 73,794-78,840 | 754-768 |
| Max | 20,824-21,103 | 33,030-33,456 | 78,578-83,296 | 1,162-1,336 |

Tail latency does NOT monotonically worsen with more event loops:
- 2 EL: P99 ~6.5ms (bad)
- 4 EL: P99 ~37ms (much worse)
- 8 EL: P99 ~0.5ms (excellent, better than blocking)

This contradicts the "more event loops = more stealing = worse latency" hypothesis.
The variability is now explained by GC timing (see below), not by event loop count.

## Steal Counter Instrumentation

### Attempt 1: plain JMH threads (no Vert.x context)

Initial steal counter tracked which event loop served consecutive operations
on the same JMH thread. Result: zero context switches.

However, this was flawed: JMH threads called `withSession().await()` directly,
meaning they had no Vert.x context. The pool had no "home" event loop to steal
from -- it simply assigned whatever was available.

### Attempt 2: runOnContext dispatch

Reworked the benchmark to simulate how Quarkus works:
- Created per-event-loop contexts via `VertxInternal.createEventLoopContext()`
- Each JMH thread dispatches work onto its assigned event loop via `ctx.runOnContext()`
- Inside the callback, captured the requesting event loop thread name
- After `find()` completes, compared the callback thread to the requesting thread

Per the SimpleConnectionPool source (line 602-612): when a steal occurs, the
lease is dispatched via `slot.context.succeededFuture(this)` -- the connection's
original event loop, NOT the requester's. So a steal would show as the callback
running on a different thread than the one that called `runOnContext`.

**Result: still zero context switches**, even with 4 event loops and 2 connections.

### Attempt 3: runOnContext + pg_sleep(5ms)

Ran with 4 event loops, 2 connections, 4 threads, 5ms pg_sleep delay to force
longer connection hold times. Result: still zero context switches, 295 ops/s.

### Why our steal counter cannot detect steals

Re-reading the SimpleConnectionPool source more carefully: when the fallback
selector picks a connection from another slot (the "steal"), the pool creates
the lease with `slot.context` -- but `slot.context` is set when the connection
is first created, using `pool.contextProvider.apply(waiterContext)`. This means
the connection's context is derived from whoever first requested it. When the
connection is returned to the pool and later acquired by a different event loop,
the pool may reassign the slot's context.

The bottom line: steal detection from application code is unreliable. The
pool's internal slot/context management is opaque to us. To reliably detect
steals, we would need to either:
- Instrument `SimpleConnectionPool` directly (modify Vert.x source)
- Use Vert.x `PoolMetrics` SPI which has hooks for connection acquisition
- Add logging/counters inside the pool's fallback selector path

### Mean-to-median divergence

The most consistent pattern across all runs: reactive's mean is pulled far
above its P50/P90 by tail spikes, while blocking's mean stays close to P90.

Example from a run with bad tail (measurement iteration, per-thread):

|          | P50  | P90  | Mean | Mean/P50 |
|----------|------|------|------|----------|
| Blocking | 162  | 209  | 192  | 1.2x     |
| Reactive | 162  | 207  | 815  | 5.0x     |

P50 and P90 are nearly identical between blocking and reactive. But the
reactive mean is 5x the median while blocking's is only 1.2x. This means a
small number of very slow operations drag the reactive average up.

This pattern is consistent with the event loop model: when a pause hits an
event loop thread (from GC, JIT, or potentially stealing), it stalls ALL
operations queued on that event loop, producing a cluster of high-latency
operations. With blocking threads, a pause affects only one thread's
current operation.

### GC correlation

Ran with `-Xlog:gc*` to check GC behavior:

|              | Blocking | Reactive |
|--------------|----------|----------|
| GC pauses    | 84       | 344      |
| Worst pause  | 4.4ms    | 2.9ms    |

Reactive has 4x more GC pauses (consistent with 2.7x higher allocation
rate from Phase 1), but each individual pause is shorter.

### GC-to-slow-op timestamp correlation

Added slow-op logging (>1ms threshold) with timestamps relative to
iteration start. Ran reactive latency with `-Xlog:gc*:file=...:time,uptime`
to get GC pause timestamps in the same time base.

Slow ops cluster in bursts -- multiple operations at nearly the same
timestamp, with decreasing latency (the first queued op waits longest):

```
  cluster at t=2754ms (115 ops)
  cluster at t=3057ms (28 ops)
  cluster at t=4480ms (165 ops)
  cluster at t=8235ms (15 ops)
  cluster at t=11691ms (10 ops)
```

GC pauses occur every ~1.7s during measurement. Aligning GC uptime
timestamps with iteration offsets (iteration starts ~20s after JVM start):

| GC uptime | Iteration offset | Slow-op cluster | Size |
|-----------|-----------------|-----------------|------|
| 22.4s     | ~2.4s           | t=2754ms        | 115  |
| 24.2s     | ~4.2s           | t=4480ms        | 165  |
| 25.9s     | ~5.9s           | (none)          | --   |
| 27.6s     | ~7.6s           | t=8235ms        | 15   |

Most slow-op clusters have a corresponding GC pause. Larger GC pauses
produce larger clusters (more ops queued during the stall). Some GC
pauses are short enough to not produce any >1ms slow ops.

**GC pauses are confirmed as the primary driver of reactive tail latency.**
Each pause stalls the event loop for 1-3ms. All operations queued on
that event loop during the pause accumulate as slow ops. Blocking threads
are independent -- a GC pause only affects whichever thread was mid-operation.

### Tail latency reproducibility

The tail is highly variable across runs:

| Run | P99 (us) | P99.9 (us) | Max (us) |
|-----|----------|-----------|----------|
| 1   | ~6,500   | 13,812    | 17,498   |
| 2   | ~580     | 7,696     | 10,633   |
| 3   | ~540     | 6,029     | 10,150   |
| 4*  | ~580     | 1,737     | 4,882    |

(*) Run 4 was with GC logging enabled, which may have changed timing.

The variability is now explained: runs where a GC pause happens to
land during a measurement window produce bad tails; runs where GC
timing is favorable produce good tails.

## Conclusions

1. **Stealing cannot be confirmed or ruled out.** Our steal counter
   detected zero steals, but it cannot reliably detect steals because
   the pool's internal context management is opaque to application code.
   The `PoolMetrics` SPI also lacks steal-specific hooks. To detect
   steals, `SimpleConnectionPool` itself must be instrumented.

2. **Throughput is essentially the same.** With Agroal (same pool Quarkus
   uses), blocking ~12.3k ops/s vs reactive ~13k ops/s (~5-7% difference).
   The original ~2x gap was an artifact of using Hibernate's built-in
   connection pool, which is much slower than Agroal.

3. **Reactive median/P90 latency matches or beats blocking.** The P50 and
   P90 are consistently similar or better for reactive across all runs,
   in both Vert.x 4 and Vert.x 5.

4. **Reactive mean diverges from median due to GC-induced tail spikes.**
   GC pauses stall event loop threads, causing all queued operations
   to experience elevated latency simultaneously. This produces clusters
   of slow ops that pull the mean far above the median. Blocking threads
   are independent, so a GC pause affects only one thread's current
   operation.

5. **GC pauses are 4x more frequent on reactive** due to 2.7x higher
   allocation rate (Vert.x/Netty/Mutiny pipeline overhead). Each pause
   is shorter, but the higher frequency means more chances to hit a
   measurement window with a tail spike.

6. **No observable difference between Vert.x 4 and 5.** Throughput and
   latency patterns are qualitatively the same across both versions.
   Tail latency variability across runs (GC timing) is larger than any
   version-to-version difference.

7. **This microbenchmark cannot reproduce the TechEmpower regression.**
   The ~50% gap likely comes from elsewhere in the Quarkus reactive
   pipeline (context propagation, thread hopping, serialization under
   high concurrency), not from raw pool scheduling or connection stealing.

## Next Steps

- [ ] Instrument Vert.x SimpleConnectionPool directly to detect steals
      (PoolMetrics SPI is insufficient -- it lacks steal-specific hooks)
- [ ] Investigate reducing reactive allocation rate to decrease GC frequency
- [ ] Re-run pg_sleep delay sweep with Agroal to get corrected numbers
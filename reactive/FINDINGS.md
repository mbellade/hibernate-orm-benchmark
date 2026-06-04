# Connection Pool Stealing Benchmark - Findings

Investigation of Quarkus issue #46817: Vert.x SimpleConnectionPool "stealing"
connections across event loops and its impact on Hibernate Reactive performance.

## Context

Franz and Sanne observed ~50% reactive performance regression vs blocking ORM
in TechEmpower benchmarks. The hypothesis: Vert.x's connection pool steals
connections from other event loops' slots when the local slot is empty, breaking
thread/connection affinity and causing cascading degradation.

## Setup

- ORM 7.4.0.Final (blocking, Agroal pool) vs Hibernate Reactive 4.4.0.Final (Vert.x 5.0.12)
- Local PostgreSQL 18.4
- Simple PK lookup: `find(Fortune.class, randomId)` on 10,000 rows
- JDK 21.0.9 Temurin, macOS

## Phase 1: Throughput

Base case: 2 threads, 2 connections, 2 event loops, no artificial delay.

| Benchmark | Throughput (ops/s) | Error |
|-----------|-------------------|-------|
| Blocking (Agroal) | 6,972 | +/- 33 |
| Reactive (2 EL) | 13,280 | +/- 107 |

Reactive is ~1.9x faster in raw throughput due to non-blocking I/O.

### Event loop sweep (2 threads, 2 connections, no delay)

| Event Loops | Throughput (ops/s) | Error |
|-------------|-------------------|-------|
| 2 | 12,095 | +/- 16,826 |
| 4 | 12,747 | +/- 4,364 |
| 8 | 13,169 | +/- 983 |

No throughput degradation from increasing event loop count.

### With pg_sleep delay (8 threads, 2 connections)

| Delay | Blocking (Agroal) | Reactive (8 EL) |
|-------|-------------------|-----------------|
| 0ms | 6,896 | 13,294 |
| 1ms | 636.4 +/- 6.6 | 643.0 +/- 5.3 |
| 5ms | 285.6 +/- 5.6 | 284.7 +/- 3.8 |

When pg_sleep dominates, both converge to the same throughput. No stealing
cost visible in throughput at any configuration.

**Conclusion: throughput measurement cannot detect the stealing effect.**

## Phase 2: Latency (HDR Histogram with coordinated omission avoidance)

Base case: 2 threads, 2 connections, 2 event loops, no delay.
Each side rate-limited to 75% of its own peak throughput.
- Blocking: 5,175 ops/s (75% of ~6,900)
- Reactive: 9,750 ops/s (75% of ~13,000)

Latency measured from expected start time (Gil Tene / wrk2 approach),
not actual start time. This captures queuing delays that throughput hides.

### Latency distribution (30s measurement, per-thread, microseconds)

| Percentile | Blocking | Reactive |
|------------|----------|----------|
| P50 | 181-185 | 148-159 |
| P90 | 210-215 | 196-202 |
| P99 | 395-401 | 6,443-6,910 |
| P99.9 | 5,280-5,579 | 29,458-29,884 |
| Max | 20,824-21,103 | 33,030-33,456 |

**Key finding: reactive has better median/P90 but dramatically worse tail latency.**
P99 is ~17x worse (6.4ms vs 0.4ms), P99.9 is ~5x worse (29ms vs 5.3ms).

The first warmup iteration for reactive showed P90 at ~133ms that settled down,
consistent with Sanne's description of cascading steal degradation at startup.

### Event loop count sweep

Same rate-limited setup (75% of reactive peak, 2 threads, 2 connections).
30s measurement iteration, per-thread HDR histogram, microseconds.

| Percentile | Blocking | Reactive 2 EL | Reactive 4 EL | Reactive 8 EL |
|------------|----------|---------------|---------------|---------------|
| P50 | 181-185 | 148-159 | 158-163 | 140-144 |
| P90 | 210-215 | 196-202 | 193-196 | 167-171 |
| P99 | 395-401 | 6,443-6,910 | 35,291-39,617 | 491-493 |
| P99.9 | 5,280-5,579 | 29,458-29,884 | 73,794-78,840 | 754-768 |
| Max | 20,824-21,103 | 33,030-33,456 | 78,578-83,296 | 1,162-1,336 |

**Unexpected result.** Tail latency does NOT monotonically worsen with more
event loops. Instead:
- 2 EL: P99 ~6.5ms (bad)
- 4 EL: P99 ~37ms (much worse)
- 8 EL: P99 ~0.5ms (excellent, better than blocking)

This contradicts the "more event loops = more stealing = worse latency" hypothesis.
The 8-EL configuration has the best tail latency of any reactive run, even better
than blocking. The mechanism behind the 2-EL and 4-EL tail spikes is unknown.

Possible explanations (unverified):
- At 2 and 4 ELs, the event loop thread count aligns with CPU scheduling in a way
  that causes occasional long pauses (e.g. both ELs on the same core)
- The Vert.x pool's connection slot distribution interacts differently when
  slots outnumber connections (8 EL, 2 conn) vs match (2 EL, 2 conn)
- JVM thread scheduling artifacts on this specific machine (macOS, 10-core M1/M2)

## Next Steps

- [ ] Flamegraph analysis of the 2-EL and 4-EL tail spikes (async-profiler wall clock)
- [ ] Steal counter instrumentation to correlate steals with tail spikes
- [ ] Re-run on Linux to rule out macOS thread scheduling artifacts
- [ ] Test with higher connection counts to see if the pattern holds

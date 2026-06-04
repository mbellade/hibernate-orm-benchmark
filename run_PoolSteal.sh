#!/bin/bash

# Pool Steal Benchmark: Blocking ORM (Agroal) vs Hibernate Reactive (Vert.x pool)
#
# This script runs the default Phase 1 (throughput) and optional Phase 2 (latency)
# benchmarks. For ad-hoc runs with custom parameters, build the JMH jar once and
# invoke java -jar directly.
#
# Build the JMH jar:
#   ./gradlew :hibernate-orm-benchmark-reactive:jmhJar -Porm=perf
#
# JMH jar location:
#   reactive/target/libs/hibernate-orm-benchmark-reactive-1.0-SNAPSHOT-jmh.jar
#
# === Ad-hoc examples ===
#
# Blocking throughput (2 threads, 2 connections):
#   java -jar $JAR BlockingPoolStealBenchmark.throughput \
#     -t 2 -f 1 -wi 2 -w 3s -i 3 -r 5s \
#     -p targetInterArrivalNs=0 -p queryDelayMs=0
#
# Reactive throughput sweep (event loops x delay):
#   java -jar $JAR ReactivePoolStealBenchmark.throughput \
#     -t 4 -f 1 -wi 2 -w 3s -i 3 -r 5s \
#     -p targetInterArrivalNs=0 -p eventLoopCount=2,4,8 -p queryDelayMs=0,5
#
# Reactive latency with HDR histogram (rate-limited to 75% of peak):
#   java -jar $JAR ReactivePoolStealBenchmark.latency \
#     -t 2 -f 1 -wi 3 -w 5s -i 1 -r 30s \
#     -p targetInterArrivalNs=205128 -p queryDelayMs=0 -p eventLoopCount=2
#
# Longer, more reliable runs (2 forks, 5 measurement iterations):
#   java -jar $JAR BlockingPoolStealBenchmark.throughput \
#     -t 2 -f 2 -wi 3 -w 5s -i 5 -r 10s \
#     -p targetInterArrivalNs=0 -p queryDelayMs=0 -prof gc
#
# Key parameters:
#   -t <threads>                    Number of JMH threads
#   -f <forks>                      Number of JVM forks (more = more reliable)
#   -wi/-w                          Warmup iterations/time
#   -i/-r                           Measurement iterations/time
#   -p eventLoopCount=<n>           Vert.x event loop count (reactive only)
#   -p queryDelayMs=<n>             pg_sleep delay in ms (0 = no delay)
#   -p targetInterArrivalNs=<n>     Rate limiter for latency phase (0 = no limit)
#   -prof gc                        GC profiling
#   -prof async:event=cpu;...       async-profiler (see below)

function usage() {
  echo "Usage:"
  echo
  echo "  $0 <orm_version> [target_interval_ns]"
  echo
  echo "    <orm_version>           The ORM version profile:"
  echo "                              quarkus  ORM 7.4 + HR 3.4 + Vert.x 4.5 (matches Quarkus)"
  echo "                              perf     ORM 7.4 + HR 4.4 + Vert.x 5.0 (latest HR stable)"
  echo "                              7.3      ORM 7.3 + HR 4.3 + Vert.x 5.0"
  echo "                              6.6      ORM 6.6 + HR 2.4 + Vert.x 4.5"
  echo "    [target_interval_ns]    Optional: per-thread inter-arrival time in ns for latency phase"
  echo "                            If omitted, only throughput benchmarks run"
  echo
  echo "  Examples:"
  echo "    $0 quarkus              # Run throughput benchmarks only (Quarkus versions)"
  echo "    $0 perf                 # Run throughput benchmarks only (latest HR)"
  echo "    $0 quarkus 203417       # Run both throughput and latency benchmarks"
}

ORM_VERSION=$1

if [ -z "$ORM_VERSION" ]; then
	echo "ERROR: ORM version not supplied"
	usage
	exit 1
fi

TARGET_INTERVAL_NS=$2

# Detect async-profiler library
AP_PROF_ARGS=""
if [ -n "$ASYNC_PROFILER_HOME" ]; then
	if [ "$(uname)" = "Darwin" ]; then
		AP_LIB="${ASYNC_PROFILER_HOME}/lib/libasyncProfiler.dylib"
	else
		AP_LIB="${ASYNC_PROFILER_HOME}/lib/libasyncProfiler.so"
	fi
	if [ -f "$AP_LIB" ]; then
		AP_PROF_ARGS="-prof async:event=cpu;output=jfr;dir=/tmp;libPath=${AP_LIB}"
		echo "async-profiler found at ${AP_LIB}"
	else
		echo "WARNING: async-profiler library not found at ${AP_LIB}, running without it"
	fi
else
	echo "WARNING: ASYNC_PROFILER_HOME not set, running without async-profiler"
fi

./gradlew :hibernate-orm-benchmark-reactive:jmhJar -Porm=${ORM_VERSION}

JAR=reactive/target/libs/hibernate-orm-benchmark-reactive-1.0-SNAPSHOT-jmh.jar

echo ""
echo "=========================================="
echo "  Phase 1: Throughput (Blocking ORM)"
echo "=========================================="
java -jar $JAR BlockingPoolStealBenchmark.throughput \
 -t 2 -f 1 -wi 2 -w 3s -i 3 -r 5s \
 -p targetInterArrivalNs=0 -p queryDelayMs=0 \
 -prof gc \
 ${AP_PROF_ARGS}

echo ""
echo "=========================================="
echo "  Phase 1: Throughput (Reactive)"
echo "=========================================="
java -jar $JAR ReactivePoolStealBenchmark.throughput \
 -t 2 -f 1 -wi 2 -w 3s -i 3 -r 5s \
 -p targetInterArrivalNs=0 -p queryDelayMs=0 -p eventLoopCount=2 \
 -prof gc \
 ${AP_PROF_ARGS}

if [ -n "$TARGET_INTERVAL_NS" ]; then
	echo ""
	echo "=========================================="
	echo "  Phase 2: Latency (Blocking ORM)"
	echo "  targetInterArrivalNs=${TARGET_INTERVAL_NS}"
	echo "=========================================="
	java -jar $JAR BlockingPoolStealBenchmark.latency \
	 -t 2 -f 2 -wi 3 -w 5s -i 5 -r 30s \
	 -p targetInterArrivalNs=${TARGET_INTERVAL_NS} -p queryDelayMs=0 \
	 -prof gc

	echo ""
	echo "=========================================="
	echo "  Phase 2: Latency (Reactive)"
	echo "  targetInterArrivalNs=${TARGET_INTERVAL_NS}"
	echo "=========================================="
	java -jar $JAR ReactivePoolStealBenchmark.latency \
	 -t 2 -f 2 -wi 3 -w 5s -i 5 -r 30s \
	 -p targetInterArrivalNs=${TARGET_INTERVAL_NS} -p queryDelayMs=0 -p eventLoopCount=2 \
	 -prof gc
fi

# Generate flamegraphs from JFR files if async-profiler was used
if [ -n "$AP_PROF_ARGS" ]; then
	echo ""
	echo "=========================================="
	echo "  Generating flamegraphs"
	echo "=========================================="
	for benchmark in BlockingPoolStealBenchmark ReactivePoolStealBenchmark; do
		jfr_files=$(find /tmp/org.hibernate.benchmark.reactive.${benchmark}* -name "jfr-cpu.jfr" 2>/dev/null)
		if [ -n "$jfr_files" ]; then
			echo "JFR files for ${benchmark}:"
			for jfr_file in $jfr_files; do
				echo "  $jfr_file"
				java -cp ${ASYNC_PROFILER_HOME}/lib/converter.jar jfr2flame $jfr_file ${jfr_file%.jfr}-cpu-${ORM_VERSION}.html 2>/dev/null
			done
		fi
	done
fi

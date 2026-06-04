package org.hibernate.benchmark.reactive;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import org.hibernate.cfg.AvailableSettings;
import org.hibernate.cfg.Configuration;
import org.hibernate.reactive.mutiny.Mutiny;
import org.hibernate.reactive.provider.ReactiveServiceRegistryBuilder;
import org.hibernate.reactive.vertx.VertxInstance;

import io.smallrye.mutiny.Uni;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;

import org.HdrHistogram.Histogram;
import org.openjdk.jmh.annotations.AuxCounters;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

@State(Scope.Benchmark)
@Fork(2)
@Threads(2)
@Warmup(iterations = 3, time = 5)
@Measurement(iterations = 5, time = 10)
public class ReactivePoolStealBenchmark {

	@Param({"2", "4", "8"})
	int eventLoopCount;

	@Param({"0", "1", "5"})
	int queryDelayMs;

	private Mutiny.SessionFactory sessionFactory;
	private Vertx vertx;

	@Setup(Level.Trial)
	public void setup() {
		var vertxOptions = new VertxOptions().setEventLoopPoolSize( eventLoopCount );
		vertx = Vertx.vertx( vertxOptions );

		var config = new Configuration();
		config.addAnnotatedClass( Fortune.class );
		config.setProperty( AvailableSettings.JAKARTA_JDBC_URL,
				"jdbc:postgresql://localhost/hibernate_orm_test?preparedStatementCacheQueries=0" );
		config.setProperty( AvailableSettings.JAKARTA_JDBC_USER, "hibernate_orm_test" );
		config.setProperty( AvailableSettings.JAKARTA_JDBC_PASSWORD, "hibernate_orm_test" );
		config.setProperty( AvailableSettings.SHOW_SQL, "false" );
		config.setProperty( AvailableSettings.FORMAT_SQL, "false" );
		config.setProperty( AvailableSettings.HBM2DDL_AUTO, "create" );
		config.setProperty( AvailableSettings.STATEMENT_BATCH_SIZE, "0" );
		config.setProperty( "hibernate.generate_statistics", "false" );
		config.setProperty( AvailableSettings.POOL_SIZE, "2" );

		var srb = new ReactiveServiceRegistryBuilder()
				.addService( VertxInstance.class, (VertxInstance) () -> vertx )
				.applySettings( config.getProperties() );

		sessionFactory = config.buildSessionFactory( srb.build() )
				.unwrap( Mutiny.SessionFactory.class );

		populateData();
	}

	private void populateData() {
		// Populate in batches to avoid overwhelming the reactive pipeline
		final int batchSize = 500;
		for ( int batch = 0; batch < 10_000 / batchSize; batch++ ) {
			final int start = batch * batchSize;
			sessionFactory.withTransaction( (session, tx) -> {
				Uni<Void> chain = Uni.createFrom().voidItem();
				for ( int i = start; i < start + batchSize; i++ ) {
					final int idx = i;
					chain = chain.chain( () ->
							session.persist( new Fortune( idx, "fortune-message-" + idx ) ) );
				}
				return chain.chain( session::flush );
			} ).await().atMost( Duration.ofMinutes( 2 ) );
		}
	}

	@TearDown(Level.Trial)
	public void teardown() {
		if ( sessionFactory != null ) {
			sessionFactory.close();
		}
		if ( vertx != null ) {
			vertx.close().toCompletionStage().toCompletableFuture().join();
		}
	}

	@State(Scope.Thread)
	@AuxCounters(AuxCounters.Type.OPERATIONS)
	public static class Counters {
		public long queries;
	}

	// -- Phase 2: latency with HDR Histogram and rate control --

	@State(Scope.Thread)
	public static class LatencyState {
		@Param({"0"})
		long targetInterArrivalNs;

		Histogram histogram;
		long nextExpectedStartNs;

		@Setup(Level.Iteration)
		public void setup() {
			histogram = new Histogram( TimeUnit.SECONDS.toNanos( 30 ), 3 );
			nextExpectedStartNs = 0;
		}

		public long awaitExpectedStart() {
			if ( targetInterArrivalNs <= 0 ) {
				return System.nanoTime();
			}
			long now = System.nanoTime();
			if ( nextExpectedStartNs == 0 ) {
				nextExpectedStartNs = now;
			}
			long expected = nextExpectedStartNs;
			nextExpectedStartNs += targetInterArrivalNs;
			while ( System.nanoTime() < expected ) {
				Thread.onSpinWait();
			}
			return expected;
		}

		@TearDown(Level.Iteration)
		public void report() {
			if ( histogram.getTotalCount() > 0 ) {
				System.out.println();
				System.out.println( "=== Reactive - HDR Histogram (microseconds) ===" );
				System.out.printf( "  Count:  %d%n", histogram.getTotalCount() );
				System.out.printf( "  Mean:   %.1f%n", histogram.getMean() / 1000.0 );
				System.out.printf( "  P50:    %.1f%n", histogram.getValueAtPercentile( 50.0 ) / 1000.0 );
				System.out.printf( "  P90:    %.1f%n", histogram.getValueAtPercentile( 90.0 ) / 1000.0 );
				System.out.printf( "  P99:    %.1f%n", histogram.getValueAtPercentile( 99.0 ) / 1000.0 );
				System.out.printf( "  P99.9:  %.1f%n", histogram.getValueAtPercentile( 99.9 ) / 1000.0 );
				System.out.printf( "  Max:    %.1f%n", histogram.getMaxValue() / 1000.0 );
				System.out.println();
				histogram.outputPercentileDistribution( System.out, 1000.0 );
			}
		}
	}

	// -- Benchmark methods --

	private Uni<Fortune> queryWithDelay(Mutiny.Session session, int id) {
		Uni<Fortune> find = session.find( Fortune.class, id );
		if ( queryDelayMs > 0 ) {
			return session.createNativeQuery( "SELECT pg_sleep(" + (queryDelayMs / 1000.0) + ")" )
					.getSingleResult()
					.chain( () -> find );
		}
		return find;
	}

	@Benchmark
	@BenchmarkMode(Mode.Throughput)
	@OutputTimeUnit(TimeUnit.SECONDS)
	public void throughput(Blackhole bh, Counters counters) {
		int id = ThreadLocalRandom.current().nextInt( 10_000 );
		var fortune = sessionFactory.withSession( session ->
				queryWithDelay( session, id )
		).await().indefinitely();
		bh.consume( fortune );
		counters.queries++;
	}

	@Benchmark
	@BenchmarkMode(Mode.Throughput)
	@OutputTimeUnit(TimeUnit.SECONDS)
	public void latency(Blackhole bh, Counters counters, LatencyState lat) {
		long expectedStart = lat.awaitExpectedStart();

		int id = ThreadLocalRandom.current().nextInt( 10_000 );
		var fortune = sessionFactory.withSession( session ->
				queryWithDelay( session, id )
		).await().indefinitely();
		bh.consume( fortune );
		counters.queries++;

		long elapsed = System.nanoTime() - expectedStart;
		lat.histogram.recordValue( Math.max( elapsed, 0 ) );
	}

	public static void main(String[] args) throws Exception {
		var bench = new ReactivePoolStealBenchmark();
		bench.eventLoopCount = 4;
		bench.setup();

		// Diagnostic: check which event loop threads handle the work
		// Launch 2 threads to simulate the JMH @Threads(2) setup
		var threadNames = new java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger>();
		var latch = new java.util.concurrent.CountDownLatch( 4 );
		for ( int t = 0; t < 4; t++ ) {
			final int threadIdx = t;
			new Thread( () -> {
				for ( int i = 0; i < 50; i++ ) {
					int id = ThreadLocalRandom.current().nextInt( 10_000 );
					bench.sessionFactory.withSession( session -> {
						String elThread = Thread.currentThread().getName();
						threadNames.computeIfAbsent( elThread, k -> new java.util.concurrent.atomic.AtomicInteger() )
								.incrementAndGet();
						return session.find( Fortune.class, id );
					} ).await().indefinitely();
				}
				latch.countDown();
			}, "jmh-worker-" + threadIdx ).start();
		}
		latch.await();

		System.out.println( "=== Event loop thread distribution ===" );
		threadNames.forEach( (name, count) ->
				System.out.printf( "  %s: %d calls%n", name, count.get() ) );

		bench.teardown();
	}
}

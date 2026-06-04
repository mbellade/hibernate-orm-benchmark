package org.hibernate.benchmark.reactive;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import org.hibernate.SessionFactory;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.cfg.Configuration;

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

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;

@State(Scope.Benchmark)
@Fork(2)
@Threads(2)
@Warmup(iterations = 3, time = 5)
@Measurement(iterations = 5, time = 10)
public class BlockingPoolStealBenchmark {

	@Param({"0", "1", "5"})
	int queryDelayMs;

	private EntityManagerFactory emf;

	@Setup(Level.Trial)
	public void setup() {
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
		// Agroal connection pool (same as Quarkus uses)
		config.setProperty( "hibernate.agroal.maxSize", "2" );
		config.setProperty( "hibernate.agroal.minSize", "2" );
		config.setProperty( "hibernate.agroal.acquisitionTimeout_s", "30" );

		emf = config.buildSessionFactory();

		populateData();
	}

	private void populateData() {
		var em = emf.createEntityManager();
		em.getTransaction().begin();
		em.createQuery( "delete Fortune" ).executeUpdate();
		for ( int i = 0; i < 10_000; i++ ) {
			em.persist( new Fortune( i, "fortune-message-" + i ) );
			if ( i % 1000 == 0 ) {
				em.flush();
				em.clear();
			}
		}
		em.getTransaction().commit();
		em.close();
	}

	@TearDown(Level.Trial)
	public void teardown() {
		emf.unwrap( SessionFactory.class ).getSchemaManager().dropMappedObjects( false );
		emf.close();
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
			// 1 microsecond to 30 seconds, 3 significant digits
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
				System.out.println( "=== Blocking ORM - HDR Histogram (microseconds) ===" );
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

	private void executeDelay(EntityManager em) {
		if ( queryDelayMs > 0 ) {
			em.createNativeQuery( "SELECT pg_sleep(:delay)" )
					.setParameter( "delay", queryDelayMs / 1000.0 )
					.getSingleResult();
		}
	}

	@Benchmark
	@BenchmarkMode(Mode.Throughput)
	@OutputTimeUnit(TimeUnit.SECONDS)
	public void throughput(Blackhole bh, Counters counters) {
		var em = emf.createEntityManager();
		try {
			executeDelay( em );
			int id = ThreadLocalRandom.current().nextInt( 10_000 );
			var fortune = em.find( Fortune.class, id );
			bh.consume( fortune );
			counters.queries++;
		}
		finally {
			em.close();
		}
	}

	@Benchmark
	@BenchmarkMode(Mode.Throughput)
	@OutputTimeUnit(TimeUnit.SECONDS)
	public void latency(Blackhole bh, Counters counters, LatencyState lat) {
		long expectedStart = lat.awaitExpectedStart();

		var em = emf.createEntityManager();
		try {
			executeDelay( em );
			int id = ThreadLocalRandom.current().nextInt( 10_000 );
			var fortune = em.find( Fortune.class, id );
			bh.consume( fortune );
			counters.queries++;
		}
		finally {
			em.close();
		}

		long elapsed = System.nanoTime() - expectedStart;
		lat.histogram.recordValue( Math.max( elapsed, 0 ) );
	}

	public static void main(String[] args) {
		var bench = new BlockingPoolStealBenchmark();
		bench.setup();
		var counters = new Counters();
		try {
			for ( int i = 0; i < 10; i++ ) {
				bench.throughput( null, counters );
			}
			System.out.println( "Queries executed: " + counters.queries );
		}
		finally {
			bench.teardown();
		}
	}
}

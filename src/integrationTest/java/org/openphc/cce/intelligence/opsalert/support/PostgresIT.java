package org.openphc.cce.intelligence.opsalert.support;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;

/**
 * Shared Testcontainers Postgres setup for RI-63 integration tests. One container per test
 * class (not per method — that would make the suite painfully slow for no real benefit), with
 * {@code notification_tracker} truncated between tests instead. Real Postgres, not H2, because
 * {@code NotificationTrackerRepository}'s SQL depends on genuinely Postgres-specific behavior
 * (a partial unique index as an {@code ON CONFLICT} target, {@code SELECT ... FOR UPDATE} row
 * locking, {@code to_char}) that an in-memory substitute can't faithfully reproduce — the exact
 * kind of thing this whole feature's correctness rests on (see docs/flow-diagrams.md).
 * <p>
 * {@code inbound_event_log} is created here too, even though it's owned by {@code collector-
 * service} in production (docs/data-dictionary.md) — this test double only needs the columns
 * {@code IngestionGapEvaluator} actually reads.
 */
@Testcontainers
public abstract class PostgresIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("ccedb_test")
            .withUsername("test")
            .withPassword("test");

    protected static JdbcTemplate jdbcTemplate;

    @BeforeAll
    static void setUpDatabase() {
        DataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());

        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();

        jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS inbound_event_log (
                    id              UUID NOT NULL DEFAULT gen_random_uuid(),
                    cloudevents_id  VARCHAR(50) NOT NULL,
                    source          VARCHAR(100) NOT NULL,
                    raw_payload     JSONB NOT NULL,
                    status          VARCHAR(20) NOT NULL DEFAULT 'RECEIVED',
                    received_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
                    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
                    PRIMARY KEY (id)
                )
                """);
    }

    protected static DataSource dataSource() {
        return jdbcTemplate.getDataSource();
    }

    @AfterEach
    void resetTables() {
        jdbcTemplate.execute("TRUNCATE TABLE notification_tracker");
        jdbcTemplate.execute("TRUNCATE TABLE inbound_event_log");
    }
}

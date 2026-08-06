package org.openphc.cce.intelligence.opsalert.tracker;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openphc.cce.intelligence.opsalert.support.PostgresIT;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationTrackerRepositoryIT extends PostgresIT {

    private NotificationTrackerRepository repository;
    private TransactionTemplate transactionTemplate;

    @BeforeEach
    void setUp() {
        repository = new NotificationTrackerRepository(jdbcTemplate);
        transactionTemplate = new TransactionTemplate(new JdbcTransactionManager(dataSource()));
    }

    private TrackerRow claim(String alertType, String referenceKey) {
        return transactionTemplate.execute(status -> repository.claimAndLock(alertType, referenceKey));
    }

    @Test
    void claimAndLockCreatesNewRowWithPrdFormatIncidentId() {
        TrackerRow row = claim("INGESTION_GAP", "2026-08-05T06:00:00Z");

        assertThat(row.currentTier()).isZero();
        assertThat(row.incidentId()).matches("CCE-TXN-\\d{8}-01");
    }

    @Test
    void claimAndLockIsIdempotentForTheSameOngoingIncident() {
        TrackerRow first = claim("INGESTION_GAP", "2026-08-05T06:00:00Z");
        TrackerRow second = claim("INGESTION_GAP", "2026-08-05T06:00:00Z");

        assertThat(second.id()).isEqualTo(first.id());
        assertThat(second.incidentId()).isEqualTo(first.incidentId());
        Integer rowCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM notification_tracker WHERE alert_type = 'INGESTION_GAP'", Integer.class);
        assertThat(rowCount).isEqualTo(1);
    }

    @Test
    void claimAndLockAllowsDifferentAlertTypesToShareTheSameReferenceKey() {
        // The uniqueness/lookup scope is always (alert_type, reference_key) together, never
        // reference_key alone — verified live against the real service earlier; pinned here.
        TrackerRow a = claim("TYPE_A", "2026-08-05T06:00:00Z");
        TrackerRow b = claim("TYPE_B", "2026-08-05T06:00:00Z");

        assertThat(a.id()).isNotEqualTo(b.id());
        Integer rowCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM notification_tracker", Integer.class);
        assertThat(rowCount).isEqualTo(2);
    }

    @Test
    void incidentIdSequenceIncrementsPerAlertTypePerDay() {
        TrackerRow first = claim("INGESTION_GAP", "2026-08-05T06:00:00Z");
        repository.closeActive("INGESTION_GAP");

        TrackerRow second = claim("INGESTION_GAP", "2026-08-05T14:00:00Z");

        assertThat(first.incidentId()).endsWith("-01");
        assertThat(second.incidentId()).endsWith("-02");
    }

    @Test
    void incidentIdSequenceIsIndependentPerAlertType() {
        TrackerRow a = claim("TYPE_A", "2026-08-05T06:00:00Z");
        TrackerRow b = claim("TYPE_B", "2026-08-05T06:00:00Z");

        // Both are each alert type's *first* incident today, so both get -01 — their own,
        // independent counters, not sharing one global sequence.
        assertThat(a.incidentId()).endsWith("-01");
        assertThat(b.incidentId()).endsWith("-01");
    }

    @Test
    void advanceTierUpdatesCurrentTierAndLastNotifiedAt() {
        TrackerRow row = claim("INGESTION_GAP", "2026-08-05T06:00:00Z");
        assertThat(row.lastNotifiedAt()).isNull();

        repository.advanceTier(row.id(), 1);

        TrackerRow reloaded = claim("INGESTION_GAP", "2026-08-05T06:00:00Z");
        assertThat(reloaded.currentTier()).isEqualTo(1);
        assertThat(reloaded.lastNotifiedAt()).isNotNull();
    }

    @Test
    void closeActiveResolvesTheActiveRow() {
        claim("INGESTION_GAP", "2026-08-05T06:00:00Z");

        int closed = repository.closeActive("INGESTION_GAP");

        assertThat(closed).isEqualTo(1);
        String status = jdbcTemplate.queryForObject(
                "SELECT status FROM notification_tracker WHERE alert_type = 'INGESTION_GAP'", String.class);
        assertThat(status).isEqualTo("RESOLVED");
    }

    @Test
    void closeActiveIsNoOpWhenNothingIsActive() {
        assertThat(repository.closeActive("INGESTION_GAP")).isZero();
    }

    @Test
    void closeStaleResolvesARowAnchoredToADifferentOccurrence() {
        // Simulates the missed-recovery edge case (flow-diagrams.md "Missed recovery between
        // two polls"): a tracker still ACTIVE, but the evaluator's current referenceKey has
        // moved on — proof the underlying data changed without a tick ever observing "healthy".
        claim("INGESTION_GAP", "2026-08-01T00:00:00Z");

        int closed = repository.closeStale("INGESTION_GAP", "2026-08-05T06:00:00Z");

        assertThat(closed).isEqualTo(1);
        String status = jdbcTemplate.queryForObject(
                "SELECT status FROM notification_tracker WHERE alert_type = 'INGESTION_GAP'", String.class);
        assertThat(status).isEqualTo("RESOLVED");
    }

    @Test
    void closeStaleLeavesAMatchingRowAlone() {
        claim("INGESTION_GAP", "2026-08-05T06:00:00Z");

        int closed = repository.closeStale("INGESTION_GAP", "2026-08-05T06:00:00Z");

        assertThat(closed).isZero();
        String status = jdbcTemplate.queryForObject(
                "SELECT status FROM notification_tracker WHERE alert_type = 'INGESTION_GAP'", String.class);
        assertThat(status).isEqualTo("ACTIVE");
    }

    @Test
    void closeStaleIsNoOpWhenNothingIsActive() {
        assertThat(repository.closeStale("INGESTION_GAP", "2026-08-05T06:00:00Z")).isZero();
    }

    @Test
    void concurrentClaimsForTheSameNewIncidentProduceExactlyOneRow() throws Exception {
        // The row-locking claim this service's whole concurrency-safety design rests on
        // (docs/flow-diagrams.md's worked example): two "pods" racing to create the first
        // tracker row for the same brand-new incident must not create two.
        int concurrency = 8;
        ExecutorService pool = Executors.newFixedThreadPool(concurrency);
        CountDownLatch ready = new CountDownLatch(concurrency);
        CountDownLatch go = new CountDownLatch(1);

        try {
            List<Future<TrackerRow>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < concurrency; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return transactionTemplate.execute(status ->
                            repository.claimAndLock("INGESTION_GAP", "2026-08-05T06:00:00Z"));
                }));
            }
            ready.await(5, TimeUnit.SECONDS);
            go.countDown();

            List<java.util.UUID> ids = new java.util.ArrayList<>();
            for (Future<TrackerRow> future : futures) {
                ids.add(future.get(10, TimeUnit.SECONDS).id());
            }

            assertThat(ids).containsOnly(ids.get(0));
            Integer rowCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM notification_tracker WHERE alert_type = 'INGESTION_GAP'", Integer.class);
            assertThat(rowCount).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }
}

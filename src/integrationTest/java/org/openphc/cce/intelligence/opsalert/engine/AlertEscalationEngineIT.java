package org.openphc.cce.intelligence.opsalert.engine;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openphc.cce.intelligence.opsalert.config.AlertTypeConfig;
import org.openphc.cce.intelligence.opsalert.config.OpsAlertProperties;
import org.openphc.cce.intelligence.opsalert.config.TierConfig;
import org.openphc.cce.intelligence.opsalert.notify.NotificationDispatcher;
import org.openphc.cce.intelligence.opsalert.notify.TierTemplateRenderer;
import org.openphc.cce.intelligence.opsalert.support.FakeEvaluator;
import org.openphc.cce.intelligence.opsalert.support.PostgresIT;
import org.openphc.cce.intelligence.opsalert.support.RecordingDispatcher;
import org.openphc.cce.intelligence.opsalert.tracker.NotificationTrackerRepository;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the full escalation flow — {@link AlertEscalationEngine#tick()} through a real
 * {@link NotificationTrackerRepository} against Postgres, a real {@link TierTemplateRenderer}
 * against the real templates, with only the {@link AlertEvaluator} and the mail send faked out.
 * This is the closest thing to the manual docker/psql verification done throughout RI-63's
 * development (docs/flow-diagrams.md), automated.
 */
class AlertEscalationEngineIT extends PostgresIT {

    private static final String ALERT_TYPE = "INGESTION_GAP";
    private static final int TIER1_MINUTES = 240;
    private static final int TIER2_MINUTES = 1440;
    private static final int TIER3_MINUTES = 2880;

    private FakeEvaluator evaluator;
    private RecordingDispatcher dispatcher;
    private AlertEscalationEngine engine;

    @BeforeEach
    void setUp() {
        evaluator = new FakeEvaluator(ALERT_TYPE);
        dispatcher = new RecordingDispatcher();

        TierConfig tier1 = new TierConfig(1, TIER1_MINUTES, "EMAIL", "Tier 1 subject for {duration}",
                "patience@example.com", "Patience", List.of(), "tier1");
        TierConfig tier2 = new TierConfig(2, TIER2_MINUTES, "EMAIL", "Tier 2 subject for {duration}",
                "claudel@example.com", "Claudel", List.of("patience@example.com"), "tier2");
        TierConfig tier3 = new TierConfig(3, TIER3_MINUTES, "EMAIL", "Tier 3 subject for {duration}",
                "andrew@example.com", "Andrew", List.of("claudel@example.com", "patience@example.com"), "tier3");
        OpsAlertProperties properties = new OpsAlertProperties(
                Map.of(ALERT_TYPE, new AlertTypeConfig(List.of(tier1, tier2, tier3), 0)), true);

        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        TemplateEngine templateEngine = new TemplateEngine();
        templateEngine.setTemplateResolver(resolver);

        engine = new AlertEscalationEngine(
                List.of(evaluator),
                new NotificationTrackerRepository(jdbcTemplate),
                properties,
                Map.of("EMAIL", (NotificationDispatcher) dispatcher),
                new TierTemplateRenderer(templateEngine),
                new SimpleMeterRegistry(),
                new JdbcTransactionManager(dataSource()));
    }

    private String status() {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM notification_tracker WHERE alert_type = ?", String.class, ALERT_TYPE);
    }

    private int currentTier() {
        return jdbcTemplate.queryForObject(
                "SELECT current_tier FROM notification_tracker WHERE alert_type = ? AND status = 'ACTIVE'",
                Integer.class, ALERT_TYPE);
    }

    /** Simulates time passing since tier 1 was sent, without actually waiting. */
    private void backdateOpenedAt(int minutesAgo) {
        jdbcTemplate.update(
                "UPDATE notification_tracker SET opened_at = now() - (? || ' minutes')::interval WHERE alert_type = ?",
                minutesAgo, ALERT_TYPE);
    }

    @Test
    void tier1FiresOnceForANewIncident() {
        evaluator.setUnhealthy("2026-08-05T06:00:00Z");

        engine.tick();

        assertThat(dispatcher.sent()).hasSize(1);
        assertThat(dispatcher.sent().get(0).recipient().to()).isEqualTo("patience@example.com");
        assertThat(currentTier()).isEqualTo(1);
        assertThat(status()).isEqualTo("ACTIVE");
    }

    @Test
    void tier1IsNeverResentOnSubsequentTicksOfTheSameIncident() {
        evaluator.setUnhealthy("2026-08-05T06:00:00Z");

        engine.tick();
        engine.tick();
        engine.tick();

        assertThat(dispatcher.sent()).hasSize(1);
    }

    @Test
    void tier2DoesNotFireBeforeItsThresholdElapsesSinceTier1() {
        evaluator.setUnhealthy("2026-08-05T06:00:00Z");
        engine.tick(); // tier 1 fires, opened_at = now

        engine.tick(); // still well under 24h since tier1

        assertThat(dispatcher.sent()).hasSize(1);
        assertThat(currentTier()).isEqualTo(1);
    }

    @Test
    void tier2FiresOnceElapsedTimeSinceTier1CrossesItsThreshold() {
        evaluator.setUnhealthy("2026-08-05T06:00:00Z");
        engine.tick(); // tier 1 fires
        backdateOpenedAt(TIER2_MINUTES + 5); // simulate >24h having passed since tier 1

        engine.tick();

        assertThat(dispatcher.sent()).hasSize(2);
        RecordingDispatcher.Sent tier2Send = dispatcher.sent().get(1);
        assertThat(tier2Send.recipient().to()).isEqualTo("claudel@example.com");
        assertThat(tier2Send.recipient().cc()).containsExactly("patience@example.com");
        assertThat(currentTier()).isEqualTo(2);
    }

    @Test
    void tier3FiresOnceElapsedTimeSinceTier1CrossesItsThreshold() {
        evaluator.setUnhealthy("2026-08-05T06:00:00Z");
        engine.tick(); // tier 1
        backdateOpenedAt(TIER3_MINUTES + 5); // simulate >48h since tier 1 — catches up 2 and 3 in one tick

        engine.tick();

        assertThat(dispatcher.sent()).hasSize(3);
        RecordingDispatcher.Sent tier3Send = dispatcher.sent().get(2);
        assertThat(tier3Send.recipient().to()).isEqualTo("andrew@example.com");
        assertThat(tier3Send.recipient().cc()).containsExactly("claudel@example.com", "patience@example.com");
        assertThat(currentTier()).isEqualTo(3);
    }

    @Test
    void goesSilentForeverAfterTheLastConfiguredTier() {
        evaluator.setUnhealthy("2026-08-05T06:00:00Z");
        engine.tick();
        backdateOpenedAt(TIER3_MINUTES + 5);
        engine.tick(); // catches up through tier 3

        backdateOpenedAt(TIER3_MINUTES + 999); // far beyond every configured tier
        engine.tick();
        engine.tick();

        assertThat(dispatcher.sent()).hasSize(3); // no 4th send — nothing left to escalate to
    }

    @Test
    void recoveryClosesTheTrackerAndStopsFurtherSends() {
        evaluator.setUnhealthy("2026-08-05T06:00:00Z");
        engine.tick();

        evaluator.setHealthy();
        engine.tick();

        assertThat(status()).isEqualTo("RESOLVED");
        assertThat(dispatcher.sent()).hasSize(1); // unchanged — recovery doesn't send anything
    }

    @Test
    void aNewOccurrenceAfterRecoveryStartsFreshAtTier1() {
        evaluator.setUnhealthy("2026-08-05T06:00:00Z");
        engine.tick();
        evaluator.setHealthy();
        engine.tick(); // closes the first incident

        evaluator.setUnhealthy("2026-08-06T09:00:00Z"); // a later, distinct incident
        engine.tick();

        assertThat(dispatcher.sent()).hasSize(2); // tier1 of incident 1, tier1 of incident 2
        assertThat(currentTier()).isEqualTo(1);
        Integer totalRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM notification_tracker WHERE alert_type = ?", Integer.class, ALERT_TYPE);
        assertThat(totalRows).isEqualTo(2);
    }

    @Test
    void missedRecoveryClosesTheStaleTrackerAndOpensAFreshOneInsteadOfDoubleEscalating() {
        // The exact edge case flow-diagrams.md's "Missed recovery between two polls" documents:
        // the evaluator jumps straight from unhealthy(old key) to unhealthy(new key) without a
        // tick ever landing in between to observe "healthy" — e.g. a recovery blip shorter than
        // the poll interval.
        evaluator.setUnhealthy("2026-08-05T06:00:00Z");
        engine.tick(); // tier 1 fires for the first occurrence

        evaluator.setUnhealthy("2026-08-05T14:00:00Z"); // different occurrence, no healthy tick in between
        engine.tick();

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT reference_key, status, current_tier FROM notification_tracker "
                        + "WHERE alert_type = ? ORDER BY created_at", ALERT_TYPE);
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).get("status")).isEqualTo("RESOLVED"); // the stale one, closed
        assertThat(rows.get(1).get("status")).isEqualTo("ACTIVE");   // the fresh one
        assertThat(rows.get(1).get("current_tier")).isEqualTo(1);
        assertThat(dispatcher.sent()).hasSize(2); // tier1 for each occurrence — never two ACTIVE at once
    }

    @Test
    void dispatchFailureDoesNotAdvanceTheTierAndIsRetriedNextTick() {
        evaluator.setUnhealthy("2026-08-05T06:00:00Z");
        dispatcher.failNextSend();

        engine.tick();

        assertThat(dispatcher.sent()).isEmpty(); // failed send is not recorded as sent
        Integer currentTier = jdbcTemplate.queryForObject(
                "SELECT current_tier FROM notification_tracker WHERE alert_type = ?", Integer.class, ALERT_TYPE);
        assertThat(currentTier).isZero(); // rolled back — never advanced past 0

        engine.tick(); // retry, this time it succeeds

        assertThat(dispatcher.sent()).hasSize(1);
        assertThat(currentTier()).isEqualTo(1);
    }
}

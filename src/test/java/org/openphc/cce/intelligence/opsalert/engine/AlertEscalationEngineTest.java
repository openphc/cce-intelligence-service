package org.openphc.cce.intelligence.opsalert.engine;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openphc.cce.intelligence.opsalert.config.AlertTypeConfig;
import org.openphc.cce.intelligence.opsalert.config.OpsAlertProperties;
import org.openphc.cce.intelligence.opsalert.config.TierConfig;
import org.openphc.cce.intelligence.opsalert.notify.NotificationDispatcher;
import org.openphc.cce.intelligence.opsalert.notify.RenderedTemplate;
import org.openphc.cce.intelligence.opsalert.notify.TierTemplateRenderer;
import org.openphc.cce.intelligence.opsalert.tracker.NotificationTrackerRepository;
import org.openphc.cce.intelligence.opsalert.tracker.TrackerRow;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the {@code cce.opsalert.notifications-enabled} kill switch: disabled must skip the tick
 * entirely — no evaluation, no tracker row, no dispatch — not just suppress the send.
 */
class AlertEscalationEngineTest {

    private static final String ALERT_TYPE = "INGESTION_GAP";
    private static final UUID ROW_ID = UUID.randomUUID();

    private NotificationTrackerRepository trackerRepository;
    private NotificationDispatcher dispatcher;
    private AlertEvaluator evaluator;
    private MeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        trackerRepository = mock(NotificationTrackerRepository.class);
        dispatcher = mock(NotificationDispatcher.class);
        evaluator = mock(AlertEvaluator.class);
        meterRegistry = new SimpleMeterRegistry();

        when(evaluator.alertType()).thenReturn(ALERT_TYPE);
        when(evaluator.evaluate()).thenReturn(AlertEvaluation.incident("ref-key", 300));
        when(trackerRepository.closeStale(any(), any())).thenReturn(0);

        TrackerRow freshRow = new TrackerRow(ROW_ID, 0, OffsetDateTime.now(), null, "CCE-TXN-20260101-01");
        when(trackerRepository.claimAndLock(ALERT_TYPE, "ref-key")).thenReturn(freshRow);
    }

    private AlertEscalationEngine engineWith(boolean notificationsEnabled) {
        TierConfig tier1 = new TierConfig(1, 240, "EMAIL", "subject", "tier1@example.org", "Tier1", List.of(), "tier1");
        AlertTypeConfig config = new AlertTypeConfig(List.of(tier1), 0);
        OpsAlertProperties properties = new OpsAlertProperties(Map.of(ALERT_TYPE, config), notificationsEnabled);

        TierTemplateRenderer renderer = mock(TierTemplateRenderer.class);
        when(renderer.render(any(), eq(1), any(), any())).thenReturn(new RenderedTemplate("subject", "<p>body</p>"));

        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);

        return new AlertEscalationEngine(
                List.of(evaluator),
                trackerRepository,
                properties,
                Map.of("EMAIL", dispatcher),
                renderer,
                meterRegistry,
                transactionManager);
    }

    @Test
    void skipsTheTickEntirelyWhenDisabled() throws Exception {
        engineWith(false).tick();

        verify(evaluator, never()).evaluate();
        verify(trackerRepository, never()).claimAndLock(any(), any());
        verify(trackerRepository, never()).advanceTier(any(), anyInt());
        verify(dispatcher, never()).send(any(), any());
    }

    @Test
    void dispatchesNormallyWhenEnabled() throws Exception {
        engineWith(true).tick();

        verify(dispatcher).send(any(), any());
        verify(trackerRepository).advanceTier(ROW_ID, 1);
    }

    @Test
    void skippedTickIsCountedOnItsOwnMeter() {
        engineWith(false).tick();

        assertThat(meterRegistry.get("cce.opsalert.tick.skipped").counter().count()).isEqualTo(1.0);
    }

    // ── Repeat-after-last-tier ──────────────────────────────────────────────────────────────

    private AlertEscalationEngine engineForRepeat(long repeatIntervalMinutes, int currentTier, long minutesSinceLastNotified) {
        // Opened long enough ago (3 days) that the normal tier 2/3 catch-up loop has nothing left
        // to do regardless of currentTier reached — every test below is currentTier 2 or 3, and
        // 3 days clears tier 3's own 48h threshold too, so any dispatch verified is unambiguously
        // attemptRepeat's, not the ordinary escalation loop's.
        return engineForRepeat(repeatIntervalMinutes, currentTier, minutesSinceLastNotified, Duration.ofDays(3).toMinutes());
    }

    private AlertEscalationEngine engineForRepeat(long repeatIntervalMinutes, int currentTier,
                                                   long minutesSinceLastNotified, long minutesSinceOpened) {
        TierConfig tier1 = new TierConfig(1, 240, "EMAIL", "s1", "tier1@example.org", "T1", List.of(), "tier1");
        TierConfig tier2 = new TierConfig(2, 1440, "EMAIL", "s2", "tier2@example.org", "T2", List.of(), "tier2");
        TierConfig tier3 = new TierConfig(3, 2880, "EMAIL", "s3", "tier3@example.org", "T3", List.of(), "tier3");
        AlertTypeConfig config = new AlertTypeConfig(List.of(tier1, tier2, tier3), repeatIntervalMinutes);
        OpsAlertProperties properties = new OpsAlertProperties(Map.of(ALERT_TYPE, config), true);

        TrackerRow row = new TrackerRow(ROW_ID, currentTier,
                OffsetDateTime.now().minusMinutes(minutesSinceOpened),
                OffsetDateTime.now().minusMinutes(minutesSinceLastNotified),
                "CCE-TXN-20260101-01");
        when(trackerRepository.claimAndLock(ALERT_TYPE, "ref-key")).thenReturn(row);

        TierTemplateRenderer renderer = mock(TierTemplateRenderer.class);
        when(renderer.render(any(), eq(3), any(), any())).thenReturn(new RenderedTemplate("s3", "<p>body</p>"));

        return new AlertEscalationEngine(
                List.of(evaluator),
                trackerRepository,
                properties,
                Map.of("EMAIL", dispatcher),
                renderer,
                meterRegistry,
                mock(PlatformTransactionManager.class));
    }

    @Test
    void resendsTheLastTierOnceItsDue() throws Exception {
        engineForRepeat(1440, 3, 1441).tick();

        verify(dispatcher).send(any(), any());
        verify(trackerRepository).advanceTier(ROW_ID, 3);
    }

    @Test
    void doesNotResendBeforeTheRepeatIntervalHasPassed() throws Exception {
        engineForRepeat(1440, 3, 1439).tick();

        verify(dispatcher, never()).send(any(), any());
        verify(trackerRepository, never()).advanceTier(any(), anyInt());
    }

    @Test
    void doesNotResendWhenRepeatIsNotConfigured() throws Exception {
        engineForRepeat(0, 3, 100_000).tick();

        verify(dispatcher, never()).send(any(), any());
    }

    @Test
    void doesNotResendBeforeReachingTheLastTier() throws Exception {
        // minutesSinceOpened(1500) sits past tier 2's own 1440m threshold but short of tier 3's
        // 2880m, so the ordinary catch-up loop has already sent tier 2 (matching currentTier=2)
        // and correctly stops before tier 3 - isolating that attemptRepeat also stays quiet here,
        // not just that nothing fired for some other reason.
        engineForRepeat(1440, 2, 100_000, 1500).tick();

        verify(dispatcher, never()).send(any(), any());
    }
}

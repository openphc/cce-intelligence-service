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

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the {@code cce.opsalert.notifications-enabled} kill switch: disabled must suppress the
 * actual dispatch while still advancing the tracker, so re-enabling later doesn't cause a
 * backlog of catch-up sends for whatever happened while it was off.
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
        AlertTypeConfig config = new AlertTypeConfig(List.of(tier1));
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
    void suppressesDispatchButStillAdvancesTrackerWhenDisabled() throws Exception {
        engineWith(false).tick();

        verify(dispatcher, never()).send(any(), any());
        verify(trackerRepository).advanceTier(ROW_ID, 1);
    }

    @Test
    void dispatchesNormallyWhenEnabled() throws Exception {
        engineWith(true).tick();

        verify(dispatcher).send(any(), any());
        verify(trackerRepository).advanceTier(ROW_ID, 1);
    }

    @Test
    void suppressedSendIsCountedOnItsOwnMeter() {
        engineWith(false).tick();

        assertThat(meterRegistry.get("cce.opsalert.notifications.suppressed")
                .tag("alert_type", ALERT_TYPE)
                .tag("tier", "1")
                .counter()
                .count())
                .isEqualTo(1.0);
    }
}

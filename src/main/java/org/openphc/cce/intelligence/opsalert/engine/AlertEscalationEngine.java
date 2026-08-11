package org.openphc.cce.intelligence.opsalert.engine;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.openphc.cce.intelligence.opsalert.config.AlertTypeConfig;
import org.openphc.cce.intelligence.opsalert.config.OpsAlertProperties;
import org.openphc.cce.intelligence.opsalert.config.TierConfig;
import org.openphc.cce.intelligence.opsalert.notify.NotificationDispatcher;
import org.openphc.cce.intelligence.opsalert.notify.Recipient;
import org.openphc.cce.intelligence.opsalert.notify.RenderedTemplate;
import org.openphc.cce.intelligence.opsalert.notify.TierTemplateRenderer;
import org.openphc.cce.intelligence.opsalert.tracker.NotificationTrackerRepository;
import org.openphc.cce.intelligence.opsalert.tracker.TrackerRow;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * The scheduled tick. Runs independently on every pod — no leader gate. Safety comes from
 * per-tracker-row Postgres locking inside {@link #attemptTier}, not from gating whether this
 * method is allowed to run at all. See docs/flow-diagrams.md for the full reasoning.
 * <p>
 * Escalation timing: tier 1 is measured from the incident's true start (the evaluator's
 * {@code referenceKey} anchor — see {@link AlertEvaluation}). Every tier after that is measured
 * from tier 1's own actual send time ({@code notification_tracker.opened_at}), not from the
 * incident's start and not independently of each other — matching the PRD's own wording ("24
 * hours after this alert", "48 hours after the initial technical alert"), not three independent
 * absolute checkpoints.
 */
@Component
@Slf4j
public class AlertEscalationEngine {

    private final List<AlertEvaluator> evaluators;
    private final NotificationTrackerRepository trackerRepository;
    private final OpsAlertProperties properties;
    private final Map<String, NotificationDispatcher> dispatchersByChannel;
    private final TierTemplateRenderer templateRenderer;
    private final MeterRegistry meterRegistry;
    private final TransactionTemplate transactionTemplate;

    public AlertEscalationEngine(List<AlertEvaluator> evaluators,
                                  NotificationTrackerRepository trackerRepository,
                                  OpsAlertProperties properties,
                                  Map<String, NotificationDispatcher> dispatchersByChannel,
                                  TierTemplateRenderer templateRenderer,
                                  MeterRegistry meterRegistry,
                                  PlatformTransactionManager transactionManager) {
        this.evaluators = evaluators;
        this.trackerRepository = trackerRepository;
        this.properties = properties;
        this.dispatchersByChannel = dispatchersByChannel;
        this.templateRenderer = templateRenderer;
        this.meterRegistry = meterRegistry;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Scheduled(fixedDelayString = "${cce.opsalert.poll-interval-ms:300000}")
    public void tick() {
        for (AlertEvaluator evaluator : evaluators) {
            try {
                runEvaluator(evaluator);
            } catch (Exception e) {
                log.error("opsalert: evaluator {} failed this tick", evaluator.alertType(), e);
            }
        }
    }

    private void runEvaluator(AlertEvaluator evaluator) {
        String alertType = evaluator.alertType();
        AlertEvaluation evaluation = evaluator.evaluate();

        if (evaluation.healthy()) {
            int closed = trackerRepository.closeActive(alertType);
            if (closed > 0) {
                log.info("opsalert: {} recovered — closed {} tracker(s)", alertType, closed);
            }
            return;
        }

        AlertTypeConfig config = properties.requireConfig(alertType);

        // If a tracker is still ACTIVE but anchored to a DIFFERENT referenceKey than what the
        // evaluator just computed, the gap must have recovered and reopened between two polls
        // without a tick ever observing "healthy" in between (a recovery blip shorter than the
        // poll interval — see flow-diagrams.md). That tracker is stale, not this occurrence;
        // close it before claiming/creating one for the current referenceKey, so a missed
        // recovery can't leave two trackers simultaneously escalating the same alert type.
        int staleClosed = trackerRepository.closeStale(alertType, evaluation.referenceKey());
        if (staleClosed > 0) {
            log.info("opsalert: {} — closed {} stale tracker(s) whose occurrence no longer matches current data",
                    alertType, staleClosed);
        }

        // Tier 1: the evaluator only reports "unhealthy" once tier 1's own threshold (measured
        // from the incident's true start) has been crossed, so it's always attempted here.
        // Every tier after this one is anchored to tier1Row.openedAt(), not to referenceKey.
        TrackerRow tier1Row = attemptTier(alertType, evaluation.referenceKey(), 1, config);
        if (tier1Row == null) {
            return; // dispatch failed or couldn't proceed — next tick retries tier 1
        }

        for (int tier = 2; tier <= config.lastTier(); tier++) {
            long minutesSinceTier1 = Duration.between(tier1Row.openedAt(), OffsetDateTime.now()).toMinutes();
            long threshold = config.tier(tier).thresholdMinutes();
            if (minutesSinceTier1 < threshold) {
                break; // not yet time for this tier — stop, next tick re-checks
            }
            TrackerRow result = attemptTier(alertType, evaluation.referenceKey(), tier, config);
            if (result == null) {
                break; // dispatch failed — stop catching up further this tick, next tick retries
            }
        }
    }

    /**
     * Claims and locks the tracker row, sends this tier if it's not already recorded as sent,
     * and returns the row either way (so the caller has {@code openedAt} to anchor later tiers
     * against) — {@code null} only on a genuine dispatch failure, signaling the caller to stop.
     * Each tier is its own claim-send-commit transaction (not one transaction for the whole
     * catch-up loop) — so if tier 2 fails after tier 1 already succeeded, tier 1's success stays
     * committed rather than being rolled back along with tier 2's failure.
     */
    private TrackerRow attemptTier(String alertType, String referenceKey, int tier, AlertTypeConfig config) {
        return transactionTemplate.execute(status -> {
            TrackerRow row = trackerRepository.claimAndLock(alertType, referenceKey);
            if (row.currentTier() >= tier) {
                return row; // already sent by this pod or another, on this or an earlier tick
            }

            TierConfig tierConfig = config.tier(tier);
            NotificationDispatcher dispatcher = dispatchersByChannel.get(tierConfig.channel());
            if (dispatcher == null) {
                throw new IllegalStateException("No NotificationDispatcher bean named '" + tierConfig.channel() + "'");
            }

            try {
                if (properties.notificationsEnabled()) {
                    RenderedTemplate rendered = templateRenderer.render(config, tier, referenceKey, row);
                    dispatcher.send(new Recipient(tierConfig.to(), tierConfig.cc()), rendered);
                    log.info("opsalert: sent {} tier {} to {} — subject: \"{}\"", alertType, tier, tierConfig.to(), rendered.subject());
                } else {
                    // Tracker still advances exactly as if the send succeeded, so re-enabling
                    // later doesn't fire a backlog of every tier the incident crossed while
                    // suppressed — this is a mute switch on delivery, not a pause on the engine.
                    log.info("opsalert: notifications disabled (cce.opsalert.notifications-enabled=false) — "
                            + "suppressing {} tier {} to {}", alertType, tier, tierConfig.to());
                    meterRegistry.counter("cce.opsalert.notifications.suppressed",
                            "alert_type", alertType, "tier", String.valueOf(tier)).increment();
                }
                trackerRepository.advanceTier(row.id(), tier);
                return new TrackerRow(row.id(), tier, row.openedAt(), OffsetDateTime.now(), row.incidentId());
            } catch (Exception e) {
                log.warn("opsalert: dispatch failed for {} tier {} — will retry next tick", alertType, tier, e);
                meterRegistry.counter("cce.opsalert.dispatch.failures",
                        "alert_type", alertType, "tier", String.valueOf(tier)).increment();
                status.setRollbackOnly();
                return null;
            }
        });
    }
}

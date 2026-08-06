package org.openphc.cce.intelligence.opsalert.engine;

/**
 * Result of one evaluator's tick.
 * <p>
 * {@code referenceKey} must be deterministic — the same ongoing incident must always recompute the
 * identical value (so the engine finds and reuses the same tracker row instead of duplicating it),
 * while a new occurrence must compute a different one. See docs/data-dictionary.md.
 * <p>
 * The evaluator only decides <i>whether</i> tier 1's own threshold has been crossed (that's the
 * only tier measured from {@code referenceKey} itself — the incident's true start). Which
 * <i>later</i> tiers have crossed is decided by the engine, not here, because tiers 2+ are
 * measured from tier 1's actual send time ({@code notification_tracker.opened_at}), which this
 * evaluator has no visibility into and shouldn't need to.
 */
public record AlertEvaluation(boolean healthy, String referenceKey, long minutesSinceReference) {

    public static AlertEvaluation ofHealthy() {
        return new AlertEvaluation(true, null, 0);
    }

    public static AlertEvaluation incident(String referenceKey, long minutesSinceReference) {
        return new AlertEvaluation(false, referenceKey, minutesSinceReference);
    }
}

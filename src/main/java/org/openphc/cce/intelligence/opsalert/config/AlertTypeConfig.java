package org.openphc.cce.intelligence.opsalert.config;

import java.util.List;
import java.util.Optional;

public record AlertTypeConfig(List<TierConfig> tiers, long repeatIntervalMinutes) {

    /** Disabled (no repeat) by default — existing callers/tests that don't care about the repeat
     *  behavior don't need to know this field exists. */
    public AlertTypeConfig(List<TierConfig> tiers) {
        this(tiers, 0);
    }

    /** Once the last configured tier has been sent, {@code AlertEscalationEngine} keeps resending
     *  it every {@code repeatIntervalMinutes} (measured from {@code last_notified_at}, not from
     *  when the last tier first fired) until the incident resolves — see that class's
     *  {@code attemptRepeat}. {@code <= 0} means no repeat: the engine goes fully silent after the
     *  last tier, same as before this existed. */
    public boolean hasRepeat() {
        return repeatIntervalMinutes > 0;
    }

    public Optional<TierConfig> findTier(int tier) {
        return tiers.stream().filter(t -> t.tier() == tier).findFirst();
    }

    public TierConfig tier(int tier) {
        return findTier(tier).orElseThrow(() -> new IllegalStateException("No tier " + tier + " configured"));
    }

    public int lastTier() {
        return tiers.stream().mapToInt(TierConfig::tier).max().orElse(0);
    }
}

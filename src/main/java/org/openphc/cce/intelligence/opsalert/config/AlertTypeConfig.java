package org.openphc.cce.intelligence.opsalert.config;

import java.util.List;
import java.util.Optional;

public record AlertTypeConfig(List<TierConfig> tiers, long repeatIntervalMinutes) {

    // Deliberately no auxiliary single-arg constructor here, tempting as it is to spare existing
    // tests the extra argument: Spring Boot's @ConfigurationProperties record binding failed
    // outright with a second constructor present — the whole cce.opsalert.alert-types map bound
    // to null instead of throwing, silently breaking every real (non-test) startup — confirmed
    // live. A config-binding record needs exactly one constructor; every caller passes both
    // fields explicitly instead. See application.yml for the actual production default (1440).

    /** Once the last configured tier has been sent, {@code AlertEscalationEngine} keeps resending
     *  it every {@code repeatIntervalMinutes} (measured from {@code last_notified_at}, not from
     *  when the last tier first fired) until the incident resolves — see that class's
     *  {@code attemptRepeat}. {@code <= 0} means no repeat: the engine goes fully silent after the
     *  last tier, same as before this existed — the production default is 1440 (24h), not this. */
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

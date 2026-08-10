package org.openphc.cce.intelligence.opsalert.config;

import java.util.List;
import java.util.Optional;

public record AlertTypeConfig(List<TierConfig> tiers) {

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

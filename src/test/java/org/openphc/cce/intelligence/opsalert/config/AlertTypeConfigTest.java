package org.openphc.cce.intelligence.opsalert.config;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AlertTypeConfigTest {

    private static TierConfig tier(int number) {
        return new TierConfig(number, number * 100L, "EMAIL", "subject",
                "to@example.com", "Name", List.of(), "template" + number);
    }

    @Test
    void findTierReturnsPresentForExistingTier() {
        AlertTypeConfig config = new AlertTypeConfig(List.of(tier(1), tier(2), tier(3)), 0);
        Optional<TierConfig> found = config.findTier(2);
        assertThat(found).isPresent();
        assertThat(found.get().tier()).isEqualTo(2);
    }

    @Test
    void findTierReturnsEmptyForMissingTier() {
        AlertTypeConfig config = new AlertTypeConfig(List.of(tier(1)), 0);
        assertThat(config.findTier(2)).isEmpty();
    }

    @Test
    void tierReturnsConfigForExistingTier() {
        AlertTypeConfig config = new AlertTypeConfig(List.of(tier(1), tier(2)), 0);
        assertThat(config.tier(1).template()).isEqualTo("template1");
    }

    @Test
    void tierThrowsIllegalStateForMissingTier() {
        AlertTypeConfig config = new AlertTypeConfig(List.of(tier(1)), 0);
        assertThatThrownBy(() -> config.tier(3))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No tier 3");
    }

    @Test
    void lastTierReturnsHighestConfiguredTierNumber() {
        AlertTypeConfig config = new AlertTypeConfig(List.of(tier(1), tier(3), tier(2)), 0);
        assertThat(config.lastTier()).isEqualTo(3);
    }

    @Test
    void lastTierReturnsZeroForEmptyTierList() {
        AlertTypeConfig config = new AlertTypeConfig(List.of(), 0);
        assertThat(config.lastTier()).isZero();
    }
}

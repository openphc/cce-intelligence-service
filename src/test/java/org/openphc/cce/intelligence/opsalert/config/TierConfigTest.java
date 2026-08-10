package org.openphc.cce.intelligence.opsalert.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link TierConfig}'s compact constructor is where every RI-63 config-validation bug found
 * during manual verification (missing env vars, unresolved placeholders, malformed addresses,
 * missing structural fields) actually got fixed — see docs/api-reference.md. These tests pin
 * that behavior down so a future change can't silently reopen any of them.
 */
class TierConfigTest {

    private static TierConfig validTier() {
        return new TierConfig(1, 240, "EMAIL", "subject text",
                "patience@example.com", "Patience", List.of(), "tier1");
    }

    @Test
    void validConfigConstructsWithoutException() {
        TierConfig tier = validTier();
        assertThat(tier.to()).isEqualTo("patience@example.com");
        assertThat(tier.toName()).isEqualTo("Patience");
    }

    // --- to / to-name: required, no default (see application.yml's comment block) ---

    @Test
    void nullToThrowsNamingTheEnvVar() {
        assertThatThrownBy(() -> new TierConfig(1, 240, "EMAIL", "subject",
                null, "Patience", List.of(), "tier1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("'to'")
                .hasMessageContaining("ALERT_EMAIL_TIER1");
    }

    @Test
    void blankToThrows() {
        assertThatThrownBy(() -> new TierConfig(1, 240, "EMAIL", "subject",
                "  ", "Patience", List.of(), "tier1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("'to'");
    }

    @Test
    void unresolvedPlaceholderToThrows() {
        // Confirmed empirically (see api-reference.md): Spring does NOT fail startup on its own
        // for an unset ${...} — it binds the literal placeholder text. This is the check that
        // actually catches it.
        assertThatThrownBy(() -> new TierConfig(1, 240, "EMAIL", "subject",
                "${ALERT_EMAIL_TIER1}", "Patience", List.of(), "tier1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ALERT_EMAIL_TIER1");
    }

    @Test
    void nullToNameThrowsNamingTheEnvVar() {
        assertThatThrownBy(() -> new TierConfig(2, 1440, "EMAIL", "subject",
                "claudel@example.com", null, List.of(), "tier2"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("'to-name'")
                .hasMessageContaining("ALERT_NAME_TIER2");
    }

    // --- to / cc: malformed-address detection, the same jakarta.mail parser EmailDispatcher uses ---

    @Test
    void malformedToAddressThrowsWithoutReachingSmtp() {
        assertThatThrownBy(() -> new TierConfig(1, 240, "EMAIL", "subject",
                "not-an-email,,also bad", "Patience", List.of(), "tier1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not a valid email address");
    }

    @Test
    void commaSeparatedValidToAddressesAreAccepted() {
        TierConfig tier = new TierConfig(1, 240, "EMAIL", "subject",
                "a@example.com,b@example.com", "Patience", List.of(), "tier1");
        assertThat(tier.to()).isEqualTo("a@example.com,b@example.com");
    }

    @Test
    void malformedCcAddressThrows() {
        // A bare word with no "@" (e.g. "not-an-email") is actually accepted by jakarta.mail's
        // parser — it's syntactic, not semantic, validation, and doesn't require a domain.
        // Confirmed empirically rather than assumed. What it DOES reject is this shape: an
        // unescaped stray comma breaking the address-list grammar.
        assertThatThrownBy(() -> new TierConfig(2, 1440, "EMAIL", "subject",
                "claudel@example.com", "Claudel", List.of("not-an-email,,also bad"), "tier2"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not a valid email address");
    }

    @Test
    void commaSeparatedValidCcAddressesAreAccepted() {
        TierConfig tier = new TierConfig(2, 1440, "EMAIL", "subject",
                "claudel@example.com", "Claudel", List.of("a@example.com,b@example.com"), "tier2");
        assertThat(tier.cc()).containsExactly("a@example.com,b@example.com");
    }

    @Test
    void unresolvedPlaceholderInCcThrows() {
        assertThatThrownBy(() -> new TierConfig(2, 1440, "EMAIL", "subject",
                "claudel@example.com", "Claudel", List.of("${ALERT_EMAIL_TIER1}"), "tier2"))
                .isInstanceOf(IllegalStateException.class);
    }

    // --- cc: unlike to/to-name, empty/null is legitimate (tier 1 has nobody to loop in) ---

    @Test
    void nullCcListIsAllowed() {
        TierConfig tier = new TierConfig(1, 240, "EMAIL", "subject",
                "patience@example.com", "Patience", null, "tier1");
        assertThat(tier.cc()).isNull();
    }

    @Test
    void emptyCcListIsAllowed() {
        TierConfig tier = new TierConfig(1, 240, "EMAIL", "subject",
                "patience@example.com", "Patience", List.of(), "tier1");
        assertThat(tier.cc()).isEmpty();
    }

    // --- channel / subject / template: not env-var-backed, but still required —
    // the gap identified and fixed after to/to-name's validation already existed ---

    @Test
    void blankChannelThrowsNamingTheTierAndField() {
        assertThatThrownBy(() -> new TierConfig(2, 1440, "  ", "subject",
                "claudel@example.com", "Claudel", List.of(), "tier2"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("tier 2")
                .hasMessageContaining("'channel'");
    }

    @Test
    void nullSubjectThrows() {
        assertThatThrownBy(() -> new TierConfig(1, 240, "EMAIL", null,
                "patience@example.com", "Patience", List.of(), "tier1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("'subject'");
    }

    @Test
    void blankTemplateThrows() {
        assertThatThrownBy(() -> new TierConfig(3, 2880, "EMAIL", "subject",
                "andrew@example.com", "Andrew", List.of(), " "))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("tier 3")
                .hasMessageContaining("'template'");
    }
}

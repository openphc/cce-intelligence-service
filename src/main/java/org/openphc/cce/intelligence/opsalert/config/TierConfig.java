package org.openphc.cce.intelligence.opsalert.config;

import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;

import java.util.List;

/**
 * One escalation tier for an alert type. One channel per tier, deliberately —
 * see docs/architecture-overview.md for why fan-out to multiple channels isn't supported here.
 * <p>
 * {@code to} and each entry of {@code cc} accept a single address OR a comma-separated list of
 * addresses in one env var — e.g. {@code ALERT_EMAIL_TIER1=a@x.com,b@x.com}. Adding or removing
 * a recipient is then just an env var value change (a redeploy/restart to pick it up, but no
 * YAML edit and no rebuild), not a new list entry in application.yml. See EmailDispatcher for
 * where the list actually gets parsed and sent, and api-reference.md for the full explanation.
 */
public record TierConfig(
        int tier,
        long thresholdMinutes,
        String channel,
        String subject,
        String to,
        String toName,
        List<String> cc,
        String template
) {
    // Fails application startup with a clear message if a required env var (ALERT_EMAIL_TIERn /
    // ALERT_NAME_TIERn) wasn't actually set — deliberately not relying on Spring's own
    // unresolved-placeholder behavior for this. Confirmed by testing: an unset ${...} in
    // application.yml is NOT rejected during @ConfigurationProperties binding — Spring binds the
    // literal, unresolved "${ALERT_EMAIL_TIER1}" text as the value and lets startup proceed. That
    // string is non-null and non-blank, so a bare isBlank() check misses it too — the actual
    // first sign of trouble was Gmail bouncing it as an invalid RFC 5321 address at send time.
    // Checking for the placeholder syntax explicitly is what actually catches this at bind time.
    public TierConfig {
        requireResolved(to, "to", "ALERT_EMAIL_TIER" + tier);
        requireValidAddressList(to, "to", "ALERT_EMAIL_TIER" + tier);
        requireResolved(toName, "to-name", "ALERT_NAME_TIER" + tier);
        if (cc != null) {
            for (String ccAddress : cc) {
                requireResolved(ccAddress, "cc", "one of the ALERT_EMAIL_TIER* vars referenced by tier " + tier + "'s cc list");
                requireValidAddressList(ccAddress, "cc", "one of the ALERT_EMAIL_TIER* vars referenced by tier " + tier + "'s cc list");
            }
        }
        // channel/subject/template are literal YAML values, not env-var-backed, so the
        // placeholder check above doesn't apply — but a missing one would otherwise surface
        // only at first dispatch, and fail the same way on every tick forever (see
        // AlertEscalationEngine.attemptTier's catch block). Failing fast here instead.
        requireNonBlank(channel, "channel", tier);
        requireNonBlank(subject, "subject", tier);
        requireNonBlank(template, "template", tier);
    }

    private static void requireResolved(String value, String field, String envVar) {
        if (value == null || value.isBlank() || (value.startsWith("${") && value.endsWith("}"))) {
            throw new IllegalStateException(
                    "cce.opsalert: '" + field + "' is not set (got '" + value + "') — check " + envVar + " is configured");
        }
    }

    // A malformed address (typo, stray/trailing comma, unbalanced quote) would otherwise only
    // surface as a jakarta.mail AddressException on the first dispatch attempt — and, like the
    // channel/subject/template gap above, fail identically and silently on every tick after
    // that, since it's a deterministic condition no retry fixes. Parsing eagerly here, with the
    // exact same jakarta.mail parser EmailDispatcher sends through, turns that into one clear
    // startup failure instead.
    private static void requireValidAddressList(String value, String field, String envVar) {
        try {
            InternetAddress.parse(value);
        } catch (AddressException e) {
            throw new IllegalStateException(
                    "cce.opsalert: '" + field + "' (" + envVar + ") is not a valid email address or "
                            + "comma-separated list of addresses — got '" + value + "': " + e.getMessage());
        }
    }

    private static void requireNonBlank(String value, String field, int tier) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "cce.opsalert: tier " + tier + " is missing required field '" + field + "'");
        }
    }
}

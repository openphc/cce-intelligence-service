package org.openphc.cce.intelligence.opsalert.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;

/**
 * Binds {@code cce.opsalert.alert-types.*} — externalized so a new alert type or a threshold/recipient
 * change never requires an engine code change. See docs/api-reference.md.
 * <p>
 * {@code notificationsEnabled} is a fully-silent kill switch (default {@code true}) — set
 * {@code CCE_OPSALERT_NOTIFICATIONS_ENABLED=false} to skip the scheduled tick entirely: no
 * evaluation, no tracker row, no state at all, as if the engine doesn't run while it's off.
 * Whatever happens to the real condition during that window leaves no trace and isn't caught up
 * on once re-enabled — the next tick starts fresh from whatever's true then. Deliberately not a
 * "track quietly but don't send" switch: that alternative was considered and rejected because it
 * would anchor tier 2/3 timing to the incident's true start even while muted, which is more
 * correct in the abstract but means silence still carries hidden state — this flag is meant for
 * "the feature itself is untrusted/off", not "mute delivery for a known window but keep watching".
 * See AlertEscalationEngine.tick.
 */
@ConfigurationProperties(prefix = "cce.opsalert")
public record OpsAlertProperties(Map<String, AlertTypeConfig> alertTypes, boolean notificationsEnabled) {

    public AlertTypeConfig requireConfig(String alertType) {
        AlertTypeConfig config = alertTypes.get(alertType);
        if (config == null) {
            throw new IllegalStateException("No cce.opsalert.alert-types config for '" + alertType + "'");
        }
        return config;
    }
}

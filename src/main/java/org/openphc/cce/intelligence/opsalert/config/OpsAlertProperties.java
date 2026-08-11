package org.openphc.cce.intelligence.opsalert.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;

/**
 * Binds {@code cce.opsalert.alert-types.*} — externalized so a new alert type or a threshold/recipient
 * change never requires an engine code change. See docs/api-reference.md.
 * <p>
 * {@code notificationsEnabled} is a kill switch on delivery only (default {@code true}) — set
 * {@code CCE_OPSALERT_NOTIFICATIONS_ENABLED=false} to stop actually sending without touching the
 * engine's own evaluation/tracking: incidents still open, still escalate through the tiers, and
 * still close on recovery, so nothing is lost or backlogged while notifications are muted, and
 * flipping it back on doesn't fire a flood of catch-up sends for whatever happened while it was
 * off. See AlertEscalationEngine.attemptTier.
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

package org.openphc.cce.intelligence.opsalert.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;

/**
 * Binds {@code cce.opsalert.alert-types.*} — externalized so a new alert type or a threshold/recipient
 * change never requires an engine code change. See docs/api-reference.md.
 */
@ConfigurationProperties(prefix = "cce.opsalert")
public record OpsAlertProperties(Map<String, AlertTypeConfig> alertTypes) {

    public AlertTypeConfig requireConfig(String alertType) {
        AlertTypeConfig config = alertTypes.get(alertType);
        if (config == null) {
            throw new IllegalStateException("No cce.opsalert.alert-types config for '" + alertType + "'");
        }
        return config;
    }
}

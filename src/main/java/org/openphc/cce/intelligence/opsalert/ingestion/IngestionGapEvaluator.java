package org.openphc.cce.intelligence.opsalert.ingestion;

import lombok.RequiredArgsConstructor;
import org.openphc.cce.intelligence.opsalert.config.AlertTypeConfig;
import org.openphc.cce.intelligence.opsalert.config.OpsAlertProperties;
import org.openphc.cce.intelligence.opsalert.engine.AlertEvaluation;
import org.openphc.cce.intelligence.opsalert.engine.AlertEvaluator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.OffsetDateTime;

/**
 * Reads {@code MAX(received_at)} directly from collector-service's own {@code inbound_event_log} —
 * same shared Postgres connection, no ClickHouse, no new datasource. See docs/architecture-overview.md
 * for why this table and not the analytics copy.
 * <p>
 * Only decides tier 1's crossing (the incident's true start, measured from the last successful
 * transaction) — tiers 2+ are measured from tier 1's actual send time instead, which is the
 * engine's concern (it has the tracker row), not this evaluator's. See {@link AlertEvaluation}.
 */
@Component
@RequiredArgsConstructor
public class IngestionGapEvaluator implements AlertEvaluator {

    public static final String ALERT_TYPE = "INGESTION_GAP";

    private final JdbcTemplate jdbcTemplate;
    private final OpsAlertProperties properties;

    @Override
    public String alertType() {
        return ALERT_TYPE;
    }

    @Override
    public AlertEvaluation evaluate() {
        OffsetDateTime lastReceivedAt = jdbcTemplate.queryForObject(
                "SELECT MAX(received_at) FROM inbound_event_log", OffsetDateTime.class);

        if (lastReceivedAt == null) {
            // No events at all yet — nothing to anchor an incident to.
            return AlertEvaluation.ofHealthy();
        }

        long minutesSince = Duration.between(lastReceivedAt, OffsetDateTime.now()).toMinutes();
        AlertTypeConfig config = properties.requireConfig(ALERT_TYPE);
        long tier1ThresholdMinutes = config.tier(1).thresholdMinutes();

        if (minutesSince < tier1ThresholdMinutes) {
            return AlertEvaluation.ofHealthy();
        }
        return AlertEvaluation.incident(lastReceivedAt.toString(), minutesSince);
    }
}

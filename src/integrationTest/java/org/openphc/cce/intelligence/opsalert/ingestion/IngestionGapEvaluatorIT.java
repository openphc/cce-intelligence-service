package org.openphc.cce.intelligence.opsalert.ingestion;

import org.junit.jupiter.api.Test;
import org.openphc.cce.intelligence.opsalert.config.AlertTypeConfig;
import org.openphc.cce.intelligence.opsalert.config.OpsAlertProperties;
import org.openphc.cce.intelligence.opsalert.config.TierConfig;
import org.openphc.cce.intelligence.opsalert.engine.AlertEvaluation;
import org.openphc.cce.intelligence.opsalert.support.PostgresIT;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class IngestionGapEvaluatorIT extends PostgresIT {

    private static final int TIER1_THRESHOLD_MINUTES = 240;

    private IngestionGapEvaluator evaluator() {
        TierConfig tier1 = new TierConfig(1, TIER1_THRESHOLD_MINUTES, "EMAIL", "subject",
                "to@example.com", "Name", List.of(), "tier1");
        OpsAlertProperties properties = new OpsAlertProperties(
                Map.of("INGESTION_GAP", new AlertTypeConfig(List.of(tier1), 0)), true);
        return new IngestionGapEvaluator(jdbcTemplate, properties);
    }

    private void insertEvent(OffsetDateTime receivedAt) {
        jdbcTemplate.update("""
                INSERT INTO inbound_event_log (id, cloudevents_id, source, raw_payload, received_at)
                VALUES (?, ?, 'test', '{}'::jsonb, ?)
                """, UUID.randomUUID(), "evt-" + UUID.randomUUID(), receivedAt);
    }

    @Test
    void reportsHealthyWhenNoEventsHaveEverArrived() {
        AlertEvaluation evaluation = evaluator().evaluate();
        assertThat(evaluation.healthy()).isTrue();
    }

    @Test
    void reportsHealthyWhenLastEventIsWellWithinThreshold() {
        insertEvent(OffsetDateTime.now().minusMinutes(5));
        assertThat(evaluator().evaluate().healthy()).isTrue();
    }

    @Test
    void reportsIncidentWhenGapExceedsThreshold() {
        OffsetDateTime lastSuccess = OffsetDateTime.now().minusMinutes(TIER1_THRESHOLD_MINUTES + 30);
        insertEvent(lastSuccess);

        AlertEvaluation evaluation = evaluator().evaluate();

        assertThat(evaluation.healthy()).isFalse();
        assertThat(evaluation.referenceKey()).isEqualTo(lastSuccess.toString());
        assertThat(evaluation.minutesSinceReference()).isGreaterThanOrEqualTo(TIER1_THRESHOLD_MINUTES + 29);
    }

    @Test
    void referenceKeyTracksTheMostRecentEventNotAnyEarlierOne() {
        insertEvent(OffsetDateTime.now().minusMinutes(TIER1_THRESHOLD_MINUTES + 300));
        OffsetDateTime mostRecent = OffsetDateTime.now().minusMinutes(TIER1_THRESHOLD_MINUTES + 30);
        insertEvent(mostRecent);

        AlertEvaluation evaluation = evaluator().evaluate();

        assertThat(evaluation.referenceKey()).isEqualTo(mostRecent.toString());
    }

    @Test
    void exactlyAtThresholdIsAlreadyAnIncident() {
        // evaluate()'s check is `minutesSince < threshold` for healthy — so exactly-at-threshold
        // must be unhealthy, not healthy. A boundary worth pinning explicitly.
        OffsetDateTime lastSuccess = OffsetDateTime.now().minusMinutes(TIER1_THRESHOLD_MINUTES).minusSeconds(5);
        insertEvent(lastSuccess);

        assertThat(evaluator().evaluate().healthy()).isFalse();
    }
}

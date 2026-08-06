package org.openphc.cce.intelligence.opsalert.engine;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AlertEvaluationTest {

    @Test
    void ofHealthyIsHealthyWithNoReferenceKey() {
        AlertEvaluation evaluation = AlertEvaluation.ofHealthy();
        assertThat(evaluation.healthy()).isTrue();
        assertThat(evaluation.referenceKey()).isNull();
        assertThat(evaluation.minutesSinceReference()).isZero();
    }

    @Test
    void incidentIsUnhealthyAndCarriesTheGivenFields() {
        AlertEvaluation evaluation = AlertEvaluation.incident("2026-08-05T06:00:00Z", 312);
        assertThat(evaluation.healthy()).isFalse();
        assertThat(evaluation.referenceKey()).isEqualTo("2026-08-05T06:00:00Z");
        assertThat(evaluation.minutesSinceReference()).isEqualTo(312);
    }
}

package org.openphc.cce.intelligence.opsalert.support;

import org.openphc.cce.intelligence.opsalert.engine.AlertEvaluation;
import org.openphc.cce.intelligence.opsalert.engine.AlertEvaluator;

/** A controllable {@link AlertEvaluator} test double — the test sets what the next tick sees. */
public class FakeEvaluator implements AlertEvaluator {

    private final String alertType;
    private volatile AlertEvaluation nextResult = AlertEvaluation.ofHealthy();

    public FakeEvaluator(String alertType) {
        this.alertType = alertType;
    }

    public void setUnhealthy(String referenceKey) {
        this.nextResult = AlertEvaluation.incident(referenceKey, 0);
    }

    public void setHealthy() {
        this.nextResult = AlertEvaluation.ofHealthy();
    }

    @Override
    public String alertType() {
        return alertType;
    }

    @Override
    public AlertEvaluation evaluate() {
        return nextResult;
    }
}

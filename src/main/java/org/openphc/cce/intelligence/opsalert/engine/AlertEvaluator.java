package org.openphc.cce.intelligence.opsalert.engine;

/**
 * Contract an alert type implements. See docs/api-reference.md for how to add a new one —
 * implement this, annotate {@code @Component}, and add a config block under
 * {@code cce.opsalert.alert-types}. No engine code changes needed.
 */
public interface AlertEvaluator {

    /** Matches a key under {@code cce.opsalert.alert-types} and the {@code alert_type} column value. */
    String alertType();

    AlertEvaluation evaluate();
}

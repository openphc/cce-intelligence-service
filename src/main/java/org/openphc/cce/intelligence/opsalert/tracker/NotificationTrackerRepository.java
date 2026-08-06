package org.openphc.cce.intelligence.opsalert.tracker;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.UUID;

/**
 * Concurrency safety lives here, not in a leader-election component — see
 * docs/flow-diagrams.md for the full reasoning and worked example.
 * <p>
 * {@link #claimAndLock} MUST be called inside an already-open transaction (the caller,
 * {@code AlertEscalationEngine}, owns transaction boundaries via {@code TransactionTemplate}) —
 * the {@code SELECT ... FOR UPDATE} row lock is only meaningful within one.
 */
@Repository
@RequiredArgsConstructor
public class NotificationTrackerRepository {

    private final JdbcTemplate jdbcTemplate;

    /**
     * Creates the tracker row if this is a brand-new incident (harmlessly no-ops if another pod's
     * tick just created it moments earlier — the partial unique index is the guard), then locks
     * and returns the current row. Blocks if another transaction already holds this row's lock.
     * <p>
     * {@code incident_id} (the PRD's {@code CCE-TXN-YYYYMMDD-NN} format) is assigned once, only
     * on the INSERT that actually creates the row — computed via a subquery counting how many
     * trackers of this {@code alert_type} already opened today, so it's a real per-day, per-alert-
     * type sequence, not a random or timestamp-derived value. The {@code ON CONFLICT DO NOTHING}
     * on a re-poll of the same ongoing incident means that subquery never re-runs for it, so the
     * ID stays fixed for the incident's whole lifetime once assigned. There's a known, accepted
     * race here: two pods creating the very first tracker for two *different* alert types in the
     * same instant could compute the same sequence number for that day, since the count isn't
     * taken under a lock spanning both inserts — acceptable because, in practice, only one
     * incident of a given alert_type is ever active at a time, so this can't happen for the same
     * alert_type, and a same-day collision across two different alert types is cosmetic (doesn't
     * affect dedup, delivery, or escalation correctness) rather than a real dedup race.
     */
    public TrackerRow claimAndLock(String alertType, String referenceKey) {
        jdbcTemplate.update("""
                INSERT INTO notification_tracker (alert_type, reference_key, incident_id)
                SELECT ?, ?, 'CCE-TXN-' || to_char(now(), 'YYYYMMDD') || '-' || to_char(
                    COALESCE((SELECT COUNT(*) FROM notification_tracker
                              WHERE alert_type = ? AND opened_at::date = CURRENT_DATE), 0) + 1,
                    'FM00')
                ON CONFLICT (alert_type, reference_key) WHERE status = 'ACTIVE' DO NOTHING
                """, alertType, referenceKey, alertType);

        return jdbcTemplate.queryForObject("""
                SELECT id, current_tier, opened_at, last_notified_at, incident_id
                FROM notification_tracker
                WHERE alert_type = ? AND reference_key = ? AND status = 'ACTIVE'
                FOR UPDATE
                """,
                (rs, rowNum) -> new TrackerRow(
                        UUID.fromString(rs.getString("id")),
                        rs.getInt("current_tier"),
                        rs.getObject("opened_at", java.time.OffsetDateTime.class),
                        rs.getObject("last_notified_at", java.time.OffsetDateTime.class),
                        rs.getString("incident_id")),
                alertType, referenceKey);
    }

    /** Only called on confirmed dispatch success — see docs/flow-diagrams.md step 5. */
    public void advanceTier(UUID id, int tier) {
        jdbcTemplate.update("""
                UPDATE notification_tracker
                SET current_tier = ?, last_notified_at = now(), updated_at = now()
                WHERE id = ?
                """, tier, id);
    }

    /** Closes whichever tracker is currently ACTIVE for this alert type; a no-op if none is. */
    public int closeActive(String alertType) {
        return jdbcTemplate.update("""
                UPDATE notification_tracker
                SET status = 'RESOLVED', resolved_at = now(), updated_at = now()
                WHERE alert_type = ? AND status = 'ACTIVE'
                """, alertType);
    }

    /**
     * Closes an ACTIVE tracker for this alert type whose {@code reference_key} no longer matches
     * the evaluator's current occurrence — evidence the underlying data recovered and broke again
     * between two polls, without a tick ever landing in between to observe "healthy" (see
     * flow-diagrams.md). Without this, that old tracker stays ACTIVE forever (orphaned, escalating
     * on its original schedule against stale data) while a second, independent tracker gets
     * created for the new occurrence — two trackers double-escalating one alert type instead of
     * one. Called every tick, before {@link #claimAndLock}, so a stale tracker is always resolved
     * before a fresh one for the current occurrence is created. A no-op — and cheap — in the
     * overwhelmingly common case where the active tracker's reference_key already matches.
     */
    public int closeStale(String alertType, String currentReferenceKey) {
        return jdbcTemplate.update("""
                UPDATE notification_tracker
                SET status = 'RESOLVED', resolved_at = now(), updated_at = now()
                WHERE alert_type = ? AND status = 'ACTIVE' AND reference_key != ?
                """, alertType, currentReferenceKey);
    }
}

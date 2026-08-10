package org.openphc.cce.intelligence.opsalert.tracker;

import java.time.OffsetDateTime;
import java.util.UUID;

public record TrackerRow(UUID id, int currentTier, OffsetDateTime openedAt, OffsetDateTime lastNotifiedAt,
                          String incidentId) {
}

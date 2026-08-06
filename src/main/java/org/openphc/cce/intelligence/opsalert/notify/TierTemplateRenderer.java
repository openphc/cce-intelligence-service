package org.openphc.cce.intelligence.opsalert.notify;

import lombok.RequiredArgsConstructor;
import org.openphc.cce.intelligence.opsalert.config.AlertTypeConfig;
import org.openphc.cce.intelligence.opsalert.config.TierConfig;
import org.openphc.cce.intelligence.opsalert.tracker.TrackerRow;
import org.springframework.stereotype.Component;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Everything a template needs is derived at render time from the tracker row + reference key —
 * no separate audit/detail table. See docs/data-dictionary.md's "deliberately absent" note.
 * <p>
 * Every person's name/role a template shows — the current recipient's own name, and any other
 * tier's contact it mentions ("...will notify Claudel...") — comes from {@code to-name} in
 * {@code application.yml}, never hardcoded in the HTML. Likewise, every "N hours" a template
 * shows — its own tier's threshold, and any other tier's threshold it mentions ("...unresolved
 * 24 hours after...") — is computed from {@code threshold-minutes}, never hardcoded either. If a
 * name, address, or threshold changes, only config changes; no template edit is needed.
 */
@Component
@RequiredArgsConstructor
public class TierTemplateRenderer {

    private static final DateTimeFormatter DISPLAY_FORMAT = DateTimeFormatter.ofPattern("dd MMMM yyyy, HH:mm 'UTC'");

    private final TemplateEngine templateEngine;

    public RenderedTemplate render(AlertTypeConfig config, int tier, String referenceKey, TrackerRow row) {
        TierConfig tierConfig = config.tier(tier);
        OffsetDateTime lastSuccess = OffsetDateTime.parse(referenceKey);
        OffsetDateTime now = OffsetDateTime.now();

        Context ctx = new Context();
        // Assigned once at tracker creation (NotificationTrackerRepository.claimAndLock), in the
        // PRD's CCE-TXN-YYYYMMDD-NN format — not derived from referenceKey, so it stays fixed for
        // the incident's whole lifetime regardless of how many times this method re-renders it.
        ctx.setVariable("incidentId", row.incidentId());
        ctx.setVariable("lastSuccessfulTransaction", lastSuccess.format(DISPLAY_FORMAT));
        ctx.setVariable("timeWithoutActivity", formatElapsed(Duration.between(lastSuccess, now)));
        ctx.setVariable("monitoredInterface", "eBuzima to HIE production");
        ctx.setVariable("affectedScope", "All facilities using the monitored interface");
        if (row.openedAt() != null) {
            ctx.setVariable("initialTechnicalAlert", row.openedAt().format(DISPLAY_FORMAT));
        }
        if (row.lastNotifiedAt() != null) {
            ctx.setVariable("operationalEscalation", row.lastNotifiedAt().format(DISPLAY_FORMAT));
        }

        // Current recipient's own name, plus every other tier's contact name a template might
        // need to mention by role — all sourced from config, none hardcoded in the HTML.
        ctx.setVariable("recipientName", tierConfig.toName());
        config.findTier(1).ifPresent(t -> ctx.setVariable("technicalContactName", t.toName()));
        config.findTier(2).ifPresent(t -> ctx.setVariable("operationalContactName", t.toName()));
        config.findTier(3).ifPresent(t -> ctx.setVariable("leadershipContactName", t.toName()));

        // Same treatment for thresholds: this tier's own ("thresholdDuration", used in the
        // subject and "for at least N" body copy) plus every other tier's, for cross-references
        // like "if unresolved N hours after this alert". Whatever threshold-minutes is
        // configured as, the email says that, never a stale "4 hours"/"24 hours"/"48 hours".
        //
        // Tiers 2+ are measured cumulatively from tier 1's actual send time (see
        // AlertEscalationEngine), so a tier's own threshold-minutes already IS "time after tier
        // 1" — technicalThreshold/operationalThreshold/leadershipThreshold below are exactly
        // that, no arithmetic needed. The one place that genuinely needs a difference, not a raw
        // threshold, is "N after the *previous* tier's escalation" (tier 3's body: "24 hours
        // after the operational escalation") — computed explicitly as this tier's threshold
        // minus the previous tier's, not assumed equal to any single tier's raw value.
        String thresholdDuration = formatThreshold(tierConfig.thresholdMinutes());
        ctx.setVariable("thresholdDuration", thresholdDuration);
        config.findTier(1).ifPresent(t -> ctx.setVariable("technicalThreshold", formatThreshold(t.thresholdMinutes())));
        config.findTier(2).ifPresent(t -> ctx.setVariable("operationalThreshold", formatThreshold(t.thresholdMinutes())));
        config.findTier(3).ifPresent(t -> ctx.setVariable("leadershipThreshold", formatThreshold(t.thresholdMinutes())));
        if (tier > 1) {
            config.findTier(tier - 1).ifPresent(previous -> {
                long gapMinutes = tierConfig.thresholdMinutes() - previous.thresholdMinutes();
                ctx.setVariable("sincePreviousTier", formatThreshold(gapMinutes));
            });
        }

        String html = templateEngine.process("opsalert/" + tierConfig.template(), ctx);
        String subject = tierConfig.subject().replace("{duration}", thresholdDuration);
        return new RenderedTemplate(subject, html);
    }

    /** Elapsed time since the last success, e.g. "623h 42m" — precise, not rounded to a phrase. */
    private String formatElapsed(Duration duration) {
        long totalMinutes = duration.toMinutes();
        long hours = totalMinutes / 60;
        long minutes = totalMinutes % 60;
        return hours + "h " + minutes + "m";
    }

    /** A configured threshold as natural language, e.g. "4 hours", "90 minutes", "1 hour 30 minutes". */
    private String formatThreshold(long minutes) {
        if (minutes < 60) {
            return minutes + (minutes == 1 ? " minute" : " minutes");
        }
        long hours = minutes / 60;
        long remainder = minutes % 60;
        String hoursPart = hours + (hours == 1 ? " hour" : " hours");
        if (remainder == 0) {
            return hoursPart;
        }
        return hoursPart + " " + remainder + (remainder == 1 ? " minute" : " minutes");
    }
}

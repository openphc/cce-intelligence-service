# API Reference

This service has **no external REST API** in this iteration — it's a background scheduled process. What follows is the internal extension surface (how to plug in a new alert type or channel) and the observability surface (what's exposed to Actuator/Prometheus).

## Extension interfaces

### `AlertEvaluator`

```java
public interface AlertEvaluator {
    String alertType();
    AlertEvaluation evaluate();
}
```

Implement one per condition being monitored. `evaluate()` runs on every scheduled tick (on whichever pod happens to be running it — there's no leader gate, see flow-diagrams.md) and returns one of:

- **No incident** — the signal is healthy right now (tier 1's threshold hasn't been crossed).
- **Incident** — a deterministic `referenceKey` identifying this occurrence, and how many minutes have elapsed since it began.
- **Incident resolved** — an `ACTIVE` tracker for this evaluator's `alertType()` should be closed.

Deliberately **not** "which tier has crossed" — an evaluator only ever decides tier 1's crossing, since that's the only tier measured from the incident's true start. Tiers 2+ are measured from tier 1's actual send time instead (`notification_tracker.opened_at`), which is `AlertEscalationEngine`'s concern, not the evaluator's — it's the one place in this design that owns tracker state, so it's the one place that can see `opened_at`. See flow-diagrams.md for the full reasoning and why this matters (it's what makes "24 hours after this alert" mean what the PRD actually says, not "24 hours after the outage began").

`IngestionGapEvaluator` is the only implementation today: `alertType() = "INGESTION_GAP"`, `referenceKey` = the last successful transaction's timestamp, "incident" reported once elapsed minutes reach tier 1's configured threshold.

**`referenceKey` is not the same thing as the email's Incident ID, on purpose.** `referenceKey` has to stay a value the evaluator can recompute *identically* on every poll tick for the same ongoing incident — that's what makes `NotificationTrackerRepository.claimAndLock`'s dedup logic correct (a re-poll of the same incident must land on the same row, not create a new one). The email's Incident ID, by contrast, is the PRD's `CCE-TXN-YYYYMMDD-NN` format — a per-day, per-`alert_type` sequence number that has to be looked up from the tracker table (how many incidents already opened today), which the evaluator has no access to by design (see above — it doesn't hold tracker state). So they're computed in different places for different reasons: `referenceKey` by the evaluator, stateless, every tick; `incidentId` by `NotificationTrackerRepository.claimAndLock`, once, only at the moment a tracker row is actually created, then carried unchanged through the rest of that incident's `TrackerRow` values (see `AlertEscalationEngine.attemptTier`) for every subsequent tier's email. Both live on the same `notification_tracker` row (`reference_key` and `incident_id` columns — data-dictionary.md) so either one traces back to the other directly.

**To add a new one:** implement the interface, annotate `@Component`. `AlertEscalationEngine` discovers every `AlertEvaluator` bean automatically (`List<AlertEvaluator>`, Spring-injected) — no engine code changes.

### `AlertTierConfig` (binds `application.yml`, not a Java interface to implement)

```yaml
cce:
  opsalert:
    alert-types:
      INGESTION_GAP:
        tiers:
          - tier: 1
            threshold-minutes: ${CCE_OPSALERT_TIER1_THRESHOLD_MINUTES}
            channel: EMAIL
            subject: "${CCE_OPSALERT_TIER1_SUBJECT:CCE Alert: No eBuzima-HIE transaction activity observed for {duration}}"
            to: ${ALERT_EMAIL_TIER1}
            to-name: ${ALERT_NAME_TIER1}
            cc: "${CCE_OPSALERT_TIER1_CC:}"
            template: tier1
          - tier: 2
            threshold-minutes: ${CCE_OPSALERT_TIER2_THRESHOLD_MINUTES}
            channel: EMAIL
            subject: "${CCE_OPSALERT_TIER2_SUBJECT:CCE Operational Escalation: eBuzima-HIE transaction interruption unresolved for {duration}}"
            to: ${ALERT_EMAIL_TIER2}
            to-name: ${ALERT_NAME_TIER2}
            cc: "${CCE_OPSALERT_TIER2_CC:${ALERT_EMAIL_TIER1}}"
            template: tier2
          - tier: 3
            threshold-minutes: ${CCE_OPSALERT_TIER3_THRESHOLD_MINUTES}
            channel: EMAIL
            subject: "${CCE_OPSALERT_TIER3_SUBJECT:CCE Leadership Escalation: eBuzima-HIE transaction interruption unresolved for {duration}}"
            to: ${ALERT_EMAIL_TIER3}
            to-name: ${ALERT_NAME_TIER3}
            cc: "${CCE_OPSALERT_TIER3_CC:${ALERT_EMAIL_TIER2},${ALERT_EMAIL_TIER1}}"
            template: tier3
```

One channel per tier, deliberately, not a list — `current_tier` on the tracker is a single scalar, so it can only safely represent one send per tier succeeding or failing. Fanning a tier out to multiple channels needs per-channel dispatch state to stay retry-safe, which doesn't exist yet (see architecture-overview.md).

Every field is a named env var, but three different failure/default philosophies apply depending on what the field is:

**`to` / `to-name` / `threshold-minutes` — required, no default.** `ALERT_EMAIL_TIER1`/`ALERT_NAME_TIER1` etc. are named by tier position, never by whoever currently holds that tier ("Patience") — so a future name *or role* change never leaves a stale-looking variable name behind, only a value change. `to-name` is what a template's greeting and any cross-references to that person use (`Dear [[${recipientName}]],`, "...will notify [[${operationalContactName}]]..."). Nothing in `templates/opsalert/*.html` hardcodes a name — `TierTemplateRenderer` exposes every configured tier's name to every template, keyed by role (`technicalContactName` = tier 1, `operationalContactName` = tier 2, `leadershipContactName` = tier 3), since a later tier's email routinely needs to mention an earlier tier's contact by name. `TierConfig`'s compact constructor validates `to`/`to-name` at bind time and fails startup with a message naming the exact missing env var, rather than trusting Spring's own unresolved-placeholder handling (which in testing did not reliably fail fast on its own). Confirmed empirically: this fires identically whether the value is an unresolved `${...}` placeholder *or* the YAML key is omitted entirely — Spring binds a missing key to `null`, which the same check catches. A wrong default here would silently misdirect a real alert, which is worse than refusing to start.

`threshold-minutes` needs no equivalent custom check, despite being just as required — it's a `long`, not a `String`, so Spring's own numeric type conversion already rejects a missing or blank value with a clear binding-failure report of its own (confirmed empirically: `Failed to bind properties under 'cce.opsalert.alert-types.ingestiongap.tiers[0].threshold-minutes' ... Reason: failed to convert java.lang.String to long`, naming the exact property, the offending value, and the YAML line). String fields need `TierConfig`'s explicit check because Spring binds *any* string, including a literal unresolved `"${ALERT_EMAIL_TIER1}"`, without complaint; a numeric field doesn't have that gap.

**`to` accepts more than one address, comma-separated, in that one env var.** `ALERT_EMAIL_TIER1=a@x.com,b@x.com` sends to both — no new YAML list entry, no rebuild, just an env var value change (still needs the running pod to pick up the new value — a restart, not a code redeploy; see deployment-guide.md). `EmailDispatcher` parses it via `jakarta.mail.internet.InternetAddress.parse(String)`, the same RFC 822 address-list parser `jakarta.mail` itself uses — not a naive comma-split, so a quoted display name containing a comma (`"Doe, Jane" <jane@x.com>, john@x.com`) still parses correctly. `TierConfig`'s compact constructor runs that identical parse at startup, so a malformed address (typo, stray trailing comma, unbalanced quote) fails once at boot with a clear message naming the field and the bad value — confirmed empirically (`cce.opsalert: 'to' (ALERT_EMAIL_TIER1) is not a valid email address or comma-separated list of addresses — got 'not-an-email,,also bad': Local address contains control or whitespace`) — rather than only surfacing on first dispatch and then failing identically, silently, forever on every tick after. Also confirmed empirically end-to-end: a two-address `ALERT_EMAIL_TIER1` produces two separate `RCPT TO` commands in the actual SMTP transaction, not one malformed recipient.

**`cc` — its own dedicated env var (`CCE_OPSALERT_TIERn_CC`), defaulting to the natural escalation chain.** Earlier versions of this config hardcoded each tier's `cc` as a YAML list of the *other* tiers' own `${ALERT_EMAIL_TIERn}` values — which meant changing who got cc'd needed a YAML edit and a rebuild, defeating the point of everything above. `cc` is now shaped exactly like `to`: one scalar env var per tier, accepting a single address or a comma-separated list, parsed the same way at both startup and send time. What makes it different from `to` is its *default*: tier 1 has nobody earlier to loop in, so `CCE_OPSALERT_TIER1_CC` defaults to empty (`cc: "${CCE_OPSALERT_TIER1_CC:}"`, confirmed empirically to bind as a genuinely empty list — not a one-element list containing `""` — so it adds no `Cc:` header at all). Tier 2 defaults to tier 1's own `to` (`cc: "${CCE_OPSALERT_TIER2_CC:${ALERT_EMAIL_TIER1}}"`), and tier 3 defaults to both tier 2's and tier 1's (`cc: "${CCE_OPSALERT_TIER3_CC:${ALERT_EMAIL_TIER2},${ALERT_EMAIL_TIER1}}"`) — a placeholder default that itself contains two further placeholders joined by a literal comma. All of this — a scalar `${VAR:default}` binding into a `List<String>` field, an empty-string default producing an empty list, and a default with two nested placeholder references — was confirmed empirically, not assumed, including a full live run of all three tiers firing together with no `CCE_OPSALERT_TIERn_CC` set: tier 1's email had no Cc, tier 2's Cc'd tier 1's address, tier 3's Cc'd both. Setting `CCE_OPSALERT_TIERn_CC` explicitly **replaces** that tier's default chain rather than adding to it — also confirmed live (`CCE_OPSALERT_TIER2_CC=extra1@x.com,extra2@x.com` produced exactly those two `RCPT TO`s as Cc, not those plus tier 1's address). If you want the default chain *and* an extra person, the override value has to include both explicitly.

`channel` and `template` are required the same way `to`/`to-name` are, checked in the same constructor, but aren't env-var-backed (they select code — which `NotificationDispatcher` bean, which Thymeleaf file — not a value an operator would tune per deployment). Missing one names the field and tier directly (`cce.opsalert: tier 2 is missing required field 'template'`). Without this check, a missing one of these two would only surface at first dispatch attempt, and would fail identically — silently, forever — on every scheduled tick afterward, since it's a deterministic condition no retry fixes.

**`subject` — optional, defaults to the PRD's current copy.** `CCE_OPSALERT_TIER1_SUBJECT` etc. default to the current subject text. Unlike recipient identity or the threshold, a missing value here just falls back to known-correct copy rather than silently misdirecting anything or silently changing escalation timing, so the service runs correctly out-of-the-box with zero configuration — set the env var only to override. Confirmed empirically that a literal `{duration}` token survives inside a `${VAR:default}` default without being mis-parsed as a nested placeholder boundary, both for the default and for an actual override value.

**Thresholds get the identical templating treatment as names — nothing in the templates hardcodes "4 hours"/"24 hours"/"48 hours" either.** `subject` may contain the literal token `{duration}`, substituted by `TierTemplateRenderer` with that tier's own `threshold-minutes` rendered as natural language ("4 hours", "90 minutes", "1 hour 30 minutes" — whatever it actually resolves to). The HTML templates use `[[${thresholdDuration}]]` the same way for the tier's own threshold, and `[[${technicalThreshold}]]`/`[[${operationalThreshold}]]`/`[[${leadershipThreshold}]]` (tiers 1/2/3 respectively) for cross-tier mentions like "if it remains unresolved N hours after this alert" — meaningful directly as each tier's own `threshold-minutes`, since tiers 2+ already *are* "minutes after tier 1" under this design (see flow-diagrams.md). Change `threshold-minutes` for any tier and every place that number appears — subject line, body copy, every other tier's cross-reference to it — updates together, because there's exactly one source of truth for it.

One more variable exists for the one phrase that genuinely needs a *difference*, not a raw threshold: `[[${sincePreviousTier}]]`, available on any tier > 1, computed as `thisTier.thresholdMinutes - previousTier.thresholdMinutes` — tier 3's body uses it for "...and N hours after the operational escalation to Claudel", which is the gap between tier 2 and tier 3, not tier 2's own raw threshold. With the current 4h/24h/48h spacing that gap happens to equal 24h — the same number as `operationalThreshold` — which is exactly the kind of coincidence worth not relying on: change tier 3's threshold to something not evenly spaced from tier 2's and `operationalThreshold` would silently go wrong while `sincePreviousTier` stays correct.

A new `alert_type` is a new top-level key here — no schema migration, no engine change.

### `NotificationDispatcher`

```java
public interface NotificationDispatcher {
    void send(Recipient recipient, RenderedTemplate template) throws DispatchException;
}
```

`EmailDispatcher` (Spring Mail + Thymeleaf) is the only implementation today. A `WhatsAppDispatcher`/`SmsDispatcher` implements the same interface later — the escalation engine only ever calls `send()`, indifferent to what's underneath.

## Observability surface

Standard Spring Boot Actuator, exposed per `management.endpoints.web.exposure.include`:

- `GET /actuator/health` — liveness/readiness, includes the Postgres connection this service depends on.
- `GET /actuator/prometheus` — Micrometer metrics, including:
  - `cce.opsalert.dispatch.failures` (counter) — incremented when a dispatch attempt fails and the tracker rolls back (see flow-diagrams.md step 5). Tagged by `alert_type` and `tier`.
  - Standard JVM/HikariCP/Hibernate-free-JDBC pool metrics (connection pool usage matters here specifically because the row-lock design holds a connection open across the SMTP call — see deployment-guide.md).

No custom `/v1/...` controllers exist for this feature. If a future need arises to inspect current incident state outside the database directly (e.g. "is there an open incident right now"), that would be a genuinely new addition, not something already implied by this design.

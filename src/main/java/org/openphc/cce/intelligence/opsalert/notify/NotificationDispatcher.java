package org.openphc.cce.intelligence.opsalert.notify;

/**
 * The send contract. {@code EmailDispatcher} is the first implementation; a future
 * {@code WhatsAppDispatcher}/{@code SmsDispatcher} implements this same interface with zero
 * change to the escalation engine. See docs/api-reference.md.
 * <p>
 * Beans implementing this MUST be named to match the {@code channel} value used in
 * {@code cce.opsalert.alert-types.*.tiers[*].channel} (e.g. {@code @Component("EMAIL")}) — the
 * engine looks dispatchers up by that name via Spring's {@code Map<String, NotificationDispatcher>}
 * autowiring.
 */
public interface NotificationDispatcher {

    /** Throws on failure — the engine rolls back the tracker update and retries next tick. */
    void send(Recipient recipient, RenderedTemplate template) throws Exception;
}

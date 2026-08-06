package org.openphc.cce.intelligence.opsalert.support;

import org.openphc.cce.intelligence.opsalert.notify.NotificationDispatcher;
import org.openphc.cce.intelligence.opsalert.notify.Recipient;
import org.openphc.cce.intelligence.opsalert.notify.RenderedTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** Records every send attempt instead of actually sending mail; can be told to fail on demand. */
public class RecordingDispatcher implements NotificationDispatcher {

    public record Sent(Recipient recipient, RenderedTemplate template) {
    }

    private final List<Sent> sent = new ArrayList<>();
    private final AtomicBoolean failNext = new AtomicBoolean(false);

    public void failNextSend() {
        failNext.set(true);
    }

    public List<Sent> sent() {
        return sent;
    }

    @Override
    public void send(Recipient recipient, RenderedTemplate template) throws Exception {
        if (failNext.compareAndSet(true, false)) {
            throw new Exception("simulated dispatch failure");
        }
        sent.add(new Sent(recipient, template));
    }
}

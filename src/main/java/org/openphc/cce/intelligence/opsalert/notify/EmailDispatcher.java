package org.openphc.cce.intelligence.opsalert.notify;

import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * First {@link NotificationDispatcher} implementation. Real HTML (via Thymeleaf-rendered
 * {@code template.html()}) and real To/Cc headers — the two things Grafana's email contact
 * point cannot do (see docs/architecture-overview.md).
 * <p>
 * {@code recipient.to()} and each entry of {@code recipient.cc()} are parsed as an RFC 822
 * address list via {@code InternetAddress.parse} — a single address or a comma-separated list
 * both work, so adding/removing a recipient is an env var value change, not a YAML edit (see
 * TierConfig, api-reference.md). {@code TierConfig}'s own validation already parsed these same
 * strings successfully at startup, so this parse cannot fail here in practice.
 * <p>
 * The DB transaction that triggered this send is held open for the duration of this call
 * (docs/flow-diagrams.md's "load-bearing, not just tidy" note) — the mail sender's
 * connection/read timeouts (application.yml, {@code spring.mail.properties.mail.smtp.*}) are
 * what keep a hung SMTP server from holding a row lock indefinitely.
 */
@Component("EMAIL")
@RequiredArgsConstructor
public class EmailDispatcher implements NotificationDispatcher {

    private final JavaMailSender mailSender;

    @Override
    public void send(Recipient recipient, RenderedTemplate template) throws Exception {
        MimeMessage message = mailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
        helper.setTo(InternetAddress.parse(recipient.to()));
        if (recipient.cc() != null && !recipient.cc().isEmpty()) {
            List<InternetAddress> ccAddresses = new ArrayList<>();
            for (String cc : recipient.cc()) {
                ccAddresses.addAll(List.of(InternetAddress.parse(cc)));
            }
            helper.setCc(ccAddresses.toArray(new InternetAddress[0]));
        }
        helper.setSubject(template.subject());
        helper.setText(template.html(), true);
        mailSender.send(message);
    }
}

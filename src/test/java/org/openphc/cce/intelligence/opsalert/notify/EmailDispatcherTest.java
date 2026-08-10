package org.openphc.cce.intelligence.opsalert.notify;

import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EmailDispatcherTest {

    private static final String FROM_ADDRESS = "CCERwanda@medtroniclabs.org";

    private JavaMailSender mailSender;
    private EmailDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        mailSender = mock(JavaMailSender.class);
        // createMimeMessage() must return a real MimeMessage (backed by a real Session) -
        // MimeMessageHelper needs genuine header-manipulation behaviour, not a mock.
        when(mailSender.createMimeMessage())
                .thenReturn(new MimeMessage(Session.getInstance(new Properties())));

        dispatcher = new EmailDispatcher(mailSender);
        ReflectionTestUtils.setField(dispatcher, "fromAddress", FROM_ADDRESS);
    }

    @Test
    void setsFromToTheConfiguredSmtpUsername() throws Exception {
        dispatcher.send(
                new Recipient("tier1@example.org", null),
                new RenderedTemplate("subject", "<p>body</p>"));

        MimeMessage sent = captureSentMessage();
        assertThat(sent.getFrom()).extracting(Object::toString).containsExactly(FROM_ADDRESS);
    }

    @Test
    void setsToAndSubjectAndHtmlBody() throws Exception {
        dispatcher.send(
                new Recipient("tier1@example.org", null),
                new RenderedTemplate("Tier 1 alert", "<p>overdue</p>"));

        MimeMessage sent = captureSentMessage();
        assertThat(sent.getRecipients(Message.RecipientType.TO))
                .extracting(Object::toString).containsExactly("tier1@example.org");
        assertThat(sent.getRecipients(Message.RecipientType.CC)).isNull();
        assertThat(sent.getSubject()).isEqualTo("Tier 1 alert");
        assertThat(htmlBodyOf(sent)).contains("overdue");
    }

    @Test
    void parsesCommaSeparatedCcIntoMultipleRecipients() throws Exception {
        dispatcher.send(
                new Recipient("tier2@example.org", List.of("tier1a@example.org,tier1b@example.org")),
                new RenderedTemplate("subject", "<p>body</p>"));

        MimeMessage sent = captureSentMessage();
        assertThat(sent.getRecipients(Message.RecipientType.CC))
                .extracting(Object::toString)
                .containsExactly("tier1a@example.org", "tier1b@example.org");
    }

    private MimeMessage captureSentMessage() throws Exception {
        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(captor.capture());
        return captor.getValue();
    }

    // MimeMessageHelper(message, true, ...) builds a nested multipart structure (mixed wrapping
    // alternative, to leave room for attachments/inline images) even for a single HTML-only body -
    // descend until a text part turns up rather than assume a fixed depth.
    private static String htmlBodyOf(jakarta.mail.Part part) throws Exception {
        Object content = part.getContent();
        if (content instanceof String text) {
            return text;
        }
        jakarta.mail.Multipart multipart = (jakarta.mail.Multipart) content;
        for (int i = 0; i < multipart.getCount(); i++) {
            return htmlBodyOf(multipart.getBodyPart(i));
        }
        throw new AssertionError("no text part found");
    }
}

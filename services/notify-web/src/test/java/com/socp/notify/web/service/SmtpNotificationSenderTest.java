package com.socp.notify.web.service;

import com.socp.notify.web.config.NotifySmtpProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.javamail.JavaMailSender;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class SmtpNotificationSenderTest {

    @Test
    void disabledSmtpCannotReportDelivery() {
        NotifySmtpProperties properties = new NotifySmtpProperties();
        SmtpNotificationSender sender = new SmtpNotificationSender(properties, emptyProvider());

        SmtpNotificationSender.DeliveryResult result = sender.send("soc@example.com", "subject", "body");

        assertFalse(result.sent());
        assertEquals("SMTP_DISABLED", result.errorCode());
    }

    @Test
    void enabledSmtpRequiresFromAddress() {
        NotifySmtpProperties properties = new NotifySmtpProperties();
        properties.setEnabled(true);
        SmtpNotificationSender sender = new SmtpNotificationSender(properties, emptyProvider());

        SmtpNotificationSender.DeliveryResult result = sender.send("soc@example.com", "subject", "body");

        assertFalse(result.sent());
        assertEquals("SMTP_FROM_MISSING", result.errorCode());
    }

    private static ObjectProvider<JavaMailSender> emptyProvider() {
        return new ObjectProvider<>() {
            @Override public JavaMailSender getObject(Object... args) { throw new IllegalStateException(); }
            @Override public JavaMailSender getIfAvailable() { return null; }
            @Override public JavaMailSender getIfUnique() { return null; }
            @Override public JavaMailSender getObject() { throw new IllegalStateException(); }
        };
    }
}

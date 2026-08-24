package com.socp.notify.web.service;

import com.socp.notify.web.config.NotifySmtpProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

import java.util.Map;

/** Sends email notifications through the configured Spring Mail sender. */
@Component
public class SmtpNotificationSender {

    private final NotifySmtpProperties properties;
    private final ObjectProvider<JavaMailSender> mailSender;

    public SmtpNotificationSender(NotifySmtpProperties properties,
                                  ObjectProvider<JavaMailSender> mailSender) {
        this.properties = properties;
        this.mailSender = mailSender;
    }

    public DeliveryResult send(String recipient, String subject, String body) {
        if (!properties.isEnabled()) {
            return DeliveryResult.failure("SMTP_DISABLED", "SMTP delivery is disabled");
        }
        if (recipient == null || recipient.isBlank()) {
            return DeliveryResult.failure("INVALID_RECIPIENT", "email recipient is empty");
        }
        if (properties.getFrom() == null || properties.getFrom().isBlank()) {
            return DeliveryResult.failure("SMTP_FROM_MISSING", "socp.notify.smtp.from is not configured");
        }
        JavaMailSender sender = mailSender.getIfAvailable();
        if (sender == null) {
            return DeliveryResult.failure("SMTP_UNAVAILABLE", "spring.mail.host is not configured");
        }

        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(properties.getFrom());
        message.setTo(recipient.trim());
        message.setSubject(subject);
        message.setText(body);
        try {
            sender.send(message);
            return DeliveryResult.success();
        } catch (RuntimeException failure) {
            return DeliveryResult.failure("SMTP_SEND_FAILED", failure.getMessage());
        }
    }

    public record DeliveryResult(boolean sent, String errorCode, String detail) {
        static DeliveryResult success() {
            return new DeliveryResult(true, null, "SMTP message accepted by the configured mail sender");
        }

        static DeliveryResult failure(String code, String detail) {
            return new DeliveryResult(false, code, detail);
        }
    }
}

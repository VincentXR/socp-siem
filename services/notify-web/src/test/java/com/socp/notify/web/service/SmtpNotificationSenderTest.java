package com.socp.notify.web.service;

import com.socp.notify.web.config.NotifySmtpProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.javamail.JavaMailSender;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class SmtpNotificationSenderTest {

    @Test void applicationDefaultsBindFiniteSmtpAndSmtpsTimeouts() throws Exception {
        var loader = new org.springframework.boot.env.YamlPropertySourceLoader();
        var environment = new org.springframework.mock.env.MockEnvironment();
        for (var source : loader.load("notify", new org.springframework.core.io.ClassPathResource("application.yml"))) {
            environment.getPropertySources().addLast(source);
        }
        for (var source : loader.load("notify-prod", new org.springframework.core.io.ClassPathResource("application-prod.yml"))) {
            environment.getPropertySources().addFirst(source);
        }
        var mail = org.springframework.boot.context.properties.bind.Binder.get(environment)
                .bind("spring.mail", org.springframework.boot.autoconfigure.mail.MailProperties.class).get();
        for (var protocol : java.util.List.of("smtp", "smtps")) {
            for (var timeout : java.util.List.of("connectiontimeout", "timeout", "writetimeout")) {
                assertEquals("3000", mail.getProperties().get("mail." + protocol + "." + timeout));
            }
        }
    }

    @Test
    void missingTimeoutsFailBeforeAnySmtpConnection() {
        var mail = new org.springframework.mail.javamail.JavaMailSenderImpl();
        var properties = new NotifySmtpProperties();
        properties.setEnabled(true); properties.setFrom("test@example.invalid");
        ObjectProvider<JavaMailSender> provider = org.mockito.Mockito.mock(ObjectProvider.class);
        org.mockito.Mockito.when(provider.getIfAvailable()).thenReturn(mail);
        var result = new SmtpNotificationSender(properties, provider).send("ops@example.invalid", "test", "test");
        assertEquals("SMTP_TIMEOUT_INVALID", result.errorCode());
    }

    @Test @org.junit.jupiter.api.Timeout(8)
    void silentLocalSmtpServerTimesOutWithoutExposingExceptionDetails() throws Exception {
        try (var server = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress());
             var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var release = new java.util.concurrent.CountDownLatch(1);
            var accepted = executor.submit(() -> {
                try (var socket = server.accept()) {
                    release.await(5, java.util.concurrent.TimeUnit.SECONDS);
                }
                return true;
            });
            var mail = new org.springframework.mail.javamail.JavaMailSenderImpl();
            mail.setHost(server.getInetAddress().getHostAddress()); mail.setPort(server.getLocalPort());
            for (var timeout : java.util.List.of("connectiontimeout", "timeout", "writetimeout")) {
                mail.getJavaMailProperties().setProperty("mail.smtp." + timeout, "250");
            }
            var properties = new NotifySmtpProperties();
            properties.setEnabled(true); properties.setFrom("test@example.invalid");
            ObjectProvider<JavaMailSender> provider = org.mockito.Mockito.mock(ObjectProvider.class);
            org.mockito.Mockito.when(provider.getIfAvailable()).thenReturn(mail);
            long start = System.nanoTime();
            try {
                var result = new SmtpNotificationSender(properties, provider).send("ops@example.invalid", "test", "test");
                assertEquals("SMTP_SEND_FAILED", result.errorCode());
                assertFalse(result.detail().contains(mail.getHost()));
                org.junit.jupiter.api.Assertions.assertTrue(java.util.concurrent.TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start) < 3);
            } finally { release.countDown(); }
            accepted.get(2, java.util.concurrent.TimeUnit.SECONDS);
        }
    }

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

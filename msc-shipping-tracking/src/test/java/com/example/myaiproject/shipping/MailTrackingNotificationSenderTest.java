package com.example.myaiproject.shipping;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.myaiproject.shipping.notify.MailTrackingNotificationSender;
import com.example.myaiproject.shipping.service.ShippingTrackingProperties;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.lang.reflect.Proxy;
import java.util.Properties;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mock.env.MockEnvironment;

class MailTrackingNotificationSenderTest {
    @Test
    void sendReturnsFalseWhenRecipientsAreMissing() {
        ShippingTrackingProperties properties = new ShippingTrackingProperties();
        properties.setNotifyEmails("");

        boolean result = sender(properties, unusedMailSender(), "configured-user", "auth-code").send("subject", "body");

        assertFalse(result);
    }

    @Test
    void sendReturnsFalseWhenQqUsernameIsMissing() {
        ShippingTrackingProperties properties = new ShippingTrackingProperties();
        properties.setNotifyEmails("to@example.com");

        boolean result = sender(properties, unusedMailSender(), "", "auth-code").send("subject", "body");

        assertFalse(result);
    }

    @Test
    void sendReturnsFalseWhenQqAuthCodeIsMissing() {
        ShippingTrackingProperties properties = new ShippingTrackingProperties();
        properties.setNotifyEmails("to@example.com");

        boolean result = sender(properties, unusedMailSender(), "configured-user", "").send("subject", "body");

        assertFalse(result);
    }

    @Test
    void sendReturnsFalseWhenJavaMailSenderIsMissing() {
        ShippingTrackingProperties properties = new ShippingTrackingProperties();
        properties.setNotifyEmails("to@example.com");

        boolean result = sender(properties, null, "configured-user", "auth-code").send("subject", "body");

        assertFalse(result);
    }

    @Test
    void sendReturnsFalseWhenSmtpSendThrows() {
        ShippingTrackingProperties properties = new ShippingTrackingProperties();
        properties.setNotifyEmails("to@example.com");

        boolean result = sender(properties, throwingMailSender(), "configured-user", "auth-code")
                .send("subject", "body");

        assertFalse(result);
    }

    @Test
    void sendUsesSpringMailUsernameAsFromAddress() throws Exception {
        ShippingTrackingProperties properties = new ShippingTrackingProperties();
        properties.setNotifyEmails("to@example.com");
        CapturingMailSender mailSender = new CapturingMailSender();

        boolean result = sender(properties, mailSender.proxy(), "configured-user", "auth-code")
                .send("subject", "<strong>body</strong>");

        assertTrue(result);
        assertNotNull(mailSender.mimeMessage);
        assertEquals("configured-user", mailSender.mimeMessage.getFrom()[0].toString());
        assertTrue(mailSender.mimeMessage.getContent().toString().contains("<strong>body</strong>"));
    }

    @SuppressWarnings("unchecked")
    private static MailTrackingNotificationSender sender(
            ShippingTrackingProperties properties,
            JavaMailSender mailSender,
            String username,
            String password) {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.mail.username", username)
                .withProperty("spring.mail.password", password);
        return new MailTrackingNotificationSender(mailSenderProvider(mailSender), properties, environment);
    }

    private static ObjectProvider<JavaMailSender> mailSenderProvider(JavaMailSender mailSender) {
        return new ObjectProvider<>() {
            @Override
            public JavaMailSender getObject(Object... args) {
                return mailSender;
            }

            @Override
            public JavaMailSender getIfAvailable() {
                return mailSender;
            }

            @Override
            public JavaMailSender getIfUnique() {
                return mailSender;
            }

            @Override
            public JavaMailSender getObject() {
                return mailSender;
            }

            @Override
            public Iterator<JavaMailSender> iterator() {
                return mailSender == null ? List.<JavaMailSender>of().iterator() : List.of(mailSender).iterator();
            }

            @Override
            public Stream<JavaMailSender> stream() {
                return mailSender == null ? Stream.empty() : Stream.of(mailSender);
            }
        };
    }

    private static JavaMailSender unusedMailSender() {
        return (JavaMailSender) Proxy.newProxyInstance(
                JavaMailSender.class.getClassLoader(),
                new Class<?>[]{JavaMailSender.class},
                (proxy, method, args) -> {
                    throw new AssertionError("JavaMailSender should not be called.");
                });
    }

    private static JavaMailSender throwingMailSender() {
        MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
        return (JavaMailSender) Proxy.newProxyInstance(
                JavaMailSender.class.getClassLoader(),
                new Class<?>[]{JavaMailSender.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("createMimeMessage") && method.getParameterCount() == 0) {
                        return message;
                    }
                    if (method.getName().equals("send")) {
                        throw new IllegalStateException("smtp failed");
                    }
                    return null;
                });
    }

    private static final class CapturingMailSender {
        private final MimeMessage mimeMessage = new MimeMessage(Session.getInstance(new Properties()));

        private JavaMailSender proxy() {
            return (JavaMailSender) Proxy.newProxyInstance(
                    JavaMailSender.class.getClassLoader(),
                    new Class<?>[]{JavaMailSender.class},
                    (proxy, method, args) -> {
                        if (method.getName().equals("createMimeMessage") && method.getParameterCount() == 0) {
                            return mimeMessage;
                        }
                        if (method.getName().equals("send")) {
                            capture(args[0]);
                            return null;
                        }
                        return null;
                    });
        }

        private void capture(Object value) {
            if (value instanceof SimpleMailMessage || value instanceof SimpleMailMessage[]) {
                throw new AssertionError("HTML mail must be sent as MimeMessage, not SimpleMailMessage.");
            }
            if (value instanceof MimeMessage message && message != mimeMessage) {
                throw new AssertionError("Unexpected MimeMessage instance.");
            }
        }
    }
}

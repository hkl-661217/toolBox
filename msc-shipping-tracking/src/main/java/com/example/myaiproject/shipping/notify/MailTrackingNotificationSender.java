package com.example.myaiproject.shipping.notify;

import com.example.myaiproject.shipping.service.ShippingTrackingProperties;
import jakarta.mail.internet.MimeMessage;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class MailTrackingNotificationSender implements TrackingNotificationSender {
    private static final Logger log = LoggerFactory.getLogger(MailTrackingNotificationSender.class);

    private final ObjectProvider<JavaMailSender> mailSenderProvider;
    private final ShippingTrackingProperties properties;
    private final Environment environment;

    public MailTrackingNotificationSender(
            ObjectProvider<JavaMailSender> mailSenderProvider,
            ShippingTrackingProperties properties,
            Environment environment) {
        this.mailSenderProvider = mailSenderProvider;
        this.properties = properties;
        this.environment = environment;
    }

    @Override
    public boolean send(String subject, String body) {
        List<String> recipients = properties.notifyEmailList();
        if (recipients.isEmpty()) {
            log.warn("Shipping tracking notification skipped because shipping.tracking.notify-emails is empty.");
            return false;
        }
        String username = environment.getProperty("spring.mail.username");
        if (isBlank(username)) {
            log.warn("Shipping tracking notification skipped because spring.mail.username is empty.");
            return false;
        }
        if (isBlank(environment.getProperty("spring.mail.password"))) {
            log.warn("Shipping tracking notification skipped because spring.mail.password is empty.");
            return false;
        }

        JavaMailSender mailSender = mailSenderProvider.getIfAvailable();
        if (mailSender == null) {
            log.warn("Shipping tracking notification skipped because JavaMailSender is not available.");
            return false;
        }

        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, "UTF-8");
            helper.setFrom(username);
            helper.setTo(recipients.toArray(String[]::new));
            helper.setSubject(subject);
            helper.setText(body, true);
            mailSender.send(message);
            return true;
        } catch (Exception error) {
            log.error("Failed to send shipping tracking notification email.", error);
            return false;
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}

package com.example.myaiproject.shipping.notify;

import com.example.myaiproject.shipping.service.ShippingTrackingProperties;
import jakarta.mail.internet.MimeMessage;
import java.util.List;
import java.util.Properties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
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

    @Override
    public boolean sendAs(String subject, String body, String email, String smtpPassword) {
        if (isBlank(email) || isBlank(smtpPassword)) {
            log.warn("sendAs skipped: email or smtpPassword is blank.");
            return false;
        }
        try {
            JavaMailSenderImpl mailSender = buildSenderForAccount(email, smtpPassword);
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, "UTF-8");
            helper.setFrom(email);
            helper.setTo(email);
            helper.setSubject(subject);
            helper.setText(body, true);
            mailSender.send(message);
            return true;
        } catch (Exception error) {
            log.error("Failed to send notification as {}.", email, error);
            return false;
        }
    }

    /** Builds an ad-hoc JavaMailSender for one (email, smtp_password) pair using QQ SMTPS. */
    private JavaMailSenderImpl buildSenderForAccount(String email, String smtpPassword) {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(environment.getProperty("spring.mail.host", "smtp.qq.com"));
        sender.setPort(Integer.parseInt(environment.getProperty("spring.mail.port", "465")));
        sender.setProtocol(environment.getProperty("spring.mail.protocol", "smtps"));
        sender.setUsername(email);
        sender.setPassword(smtpPassword);
        sender.setDefaultEncoding("UTF-8");

        Properties props = sender.getJavaMailProperties();
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.ssl.enable", environment.getProperty("spring.mail.properties.mail.smtp.ssl.enable", "true"));
        props.put("mail.smtp.connectiontimeout", environment.getProperty("spring.mail.properties.mail.smtp.connectiontimeout", "10000"));
        props.put("mail.smtp.timeout", environment.getProperty("spring.mail.properties.mail.smtp.timeout", "10000"));
        props.put("mail.smtp.writetimeout", environment.getProperty("spring.mail.properties.mail.smtp.writetimeout", "10000"));
        return sender;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}

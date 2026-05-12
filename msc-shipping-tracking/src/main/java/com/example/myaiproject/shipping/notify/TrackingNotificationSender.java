package com.example.myaiproject.shipping.notify;

public interface TrackingNotificationSender {
    /** Send via the static spring.mail credentials + shipping.tracking.notify-emails recipient list. Used as fallback. */
    boolean send(String subject, String body);

    /** Send via per-account SMTP credentials. From = To = the supplied email. */
    boolean sendAs(String subject, String body, String email, String smtpPassword);
}

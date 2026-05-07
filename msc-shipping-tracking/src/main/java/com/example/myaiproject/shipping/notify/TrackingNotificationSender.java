package com.example.myaiproject.shipping.notify;

public interface TrackingNotificationSender {
    boolean send(String subject, String body);
}

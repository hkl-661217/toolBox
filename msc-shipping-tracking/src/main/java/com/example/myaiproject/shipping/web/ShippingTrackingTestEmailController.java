package com.example.myaiproject.shipping.web;

import com.example.myaiproject.shipping.notify.ShippingTrackingEmailTemplateBuilder;
import com.example.myaiproject.shipping.notify.TrackingNotificationSender;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/shipping-tracking")
public class ShippingTrackingTestEmailController {
    private final TrackingNotificationSender notificationSender;
    private final ShippingTrackingEmailTemplateBuilder emailTemplateBuilder;

    public ShippingTrackingTestEmailController(
            TrackingNotificationSender notificationSender,
            ShippingTrackingEmailTemplateBuilder emailTemplateBuilder) {
        this.notificationSender = notificationSender;
        this.emailTemplateBuilder = emailTemplateBuilder;
    }

    @PostMapping("/test-email")
    public TestEmailResponse sendTestEmail() {
        ShippingTrackingEmailTemplateBuilder.EmailContent email = emailTemplateBuilder.buildTestEmail();
        boolean success = notificationSender.send(email.subject(), email.htmlBody());
        return new TestEmailResponse(success);
    }

    public record TestEmailResponse(boolean success) {
    }
}

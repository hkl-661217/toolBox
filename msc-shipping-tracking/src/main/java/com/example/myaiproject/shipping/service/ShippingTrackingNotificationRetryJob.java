package com.example.myaiproject.shipping.service;

import com.example.myaiproject.shipping.model.NotificationAccount;
import com.example.myaiproject.shipping.model.ShippingTrackingBinding;
import com.example.myaiproject.shipping.model.ShippingTrackingChangeLog;
import com.example.myaiproject.shipping.model.ShippingTrackingEvent;
import com.example.myaiproject.shipping.model.ShippingTrackingEventChange;
import com.example.myaiproject.shipping.notify.ShippingTrackingEmailTemplateBuilder;
import com.example.myaiproject.shipping.notify.TrackingNotificationSender;
import com.example.myaiproject.shipping.repo.ShippingTrackingBindingRepository;
import com.example.myaiproject.shipping.repo.ShippingTrackingChangeLogRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class ShippingTrackingNotificationRetryJob {
    private static final Logger log = LoggerFactory.getLogger(ShippingTrackingNotificationRetryJob.class);

    private final ShippingTrackingChangeLogRepository changeLogRepository;
    private final ShippingTrackingBindingRepository bindingRepository;
    private final TrackingNotificationSender sender;
    private final NotificationAccountService accountService;
    private final ShippingTrackingEmailTemplateBuilder templateBuilder;
    private final ObjectMapper objectMapper;
    private final ShippingTrackingProperties properties;

    public ShippingTrackingNotificationRetryJob(
            ShippingTrackingChangeLogRepository changeLogRepository,
            ShippingTrackingBindingRepository bindingRepository,
            TrackingNotificationSender sender,
            NotificationAccountService accountService,
            ShippingTrackingEmailTemplateBuilder templateBuilder,
            ObjectMapper objectMapper,
            ShippingTrackingProperties properties) {
        this.changeLogRepository = changeLogRepository;
        this.bindingRepository = bindingRepository;
        this.sender = sender;
        this.accountService = accountService;
        this.templateBuilder = templateBuilder;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Scheduled(cron = "${shipping.tracking.retry-cron:0 */15 * * * *}")
    public void runScheduledRetry() {
        runRetryCycle();
    }

    /** Visible for tests — same logic, no @Scheduled wrapper. */
    void runRetryCycle() {
        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime ageCutoff = now.minusHours(properties.getRetryMaxAgeHours());
        List<ShippingTrackingChangeLog> pending = changeLogRepository.findPendingRetries(
                properties.getRetryMaxAttempts(),
                ageCutoff);
        if (pending.isEmpty()) {
            return;
        }
        log.info("Email retry cycle scanning {} pending change_log row(s).", pending.size());

        for (ShippingTrackingChangeLog row : pending) {
            try {
                attemptOne(row);
            } catch (Exception error) {
                log.warn("Retry attempt threw for change_log id {}; bumping count.", row.id(), error);
                safeBumpRetry(row.id());
            }
        }
    }

    private void attemptOne(ShippingTrackingChangeLog row) {
        ShippingTrackingBinding binding = bindingRepository.findById(row.bindingId()).orElse(null);
        if (binding == null) {
            log.warn("Skipping retry for change_log {}: binding {} no longer exists.",
                    row.id(), row.bindingId());
            return;
        }

        List<ShippingTrackingEventChange> changes = rebuildChanges(row);
        ShippingTrackingEmailTemplateBuilder.EmailContent email =
                templateBuilder.buildChangeNotification(
                        binding,
                        binding.lastEta(),
                        binding.lastNode(),
                        changes,
                        OffsetDateTime.now());

        boolean delivered = dispatch(email);

        OffsetDateTime now = OffsetDateTime.now();
        if (delivered) {
            changeLogRepository.markEmailSent(row.id(), now);
        } else {
            changeLogRepository.bumpRetryCount(row.id(), now);
        }
    }

    private boolean dispatch(ShippingTrackingEmailTemplateBuilder.EmailContent email) {
        List<NotificationAccount> accounts = accountService.listEnabled();
        if (accounts.isEmpty()) {
            return sender.send(email.subject(), email.htmlBody());
        }
        boolean anySuccess = false;
        for (NotificationAccount account : accounts) {
            if (sender.sendAs(email.subject(), email.htmlBody(), account.email(), account.smtpPassword())) {
                anySuccess = true;
            }
        }
        return anySuccess;
    }

    private List<ShippingTrackingEventChange> rebuildChanges(ShippingTrackingChangeLog row) {
        try {
            List<ShippingTrackingEvent> before = readEvents(row.beforeJson());
            List<ShippingTrackingEvent> after = readEvents(row.afterJson());
            List<ShippingTrackingEventChange> out = new ArrayList<>();
            int size = Math.max(before.size(), after.size());
            for (int i = 0; i < size; i++) {
                ShippingTrackingEvent b = i < before.size() ? before.get(i) : null;
                ShippingTrackingEvent a = i < after.size() ? after.get(i) : null;
                out.add(new ShippingTrackingEventChange(i + 1, "RETRY", b, a));
            }
            return out;
        } catch (Exception error) {
            log.warn("Failed to rebuild events for change_log {}; sending an empty-changes email.",
                    row.id(), error);
            return List.of();
        }
    }

    private List<ShippingTrackingEvent> readEvents(String json) throws Exception {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        return objectMapper.readValue(json, new TypeReference<List<ShippingTrackingEvent>>() {});
    }

    private void safeBumpRetry(long id) {
        try {
            changeLogRepository.bumpRetryCount(id, OffsetDateTime.now());
        } catch (Exception bumpError) {
            log.error("Failed to bump retry_count for change_log {}.", id, bumpError);
        }
    }
}

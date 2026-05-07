package com.example.myaiproject.shipping.service;

import com.example.myaiproject.shipping.client.MscTrackingClient;
import com.example.myaiproject.shipping.client.MscTrackingQueryResult;
import com.example.myaiproject.shipping.model.ShippingTrackingBinding;
import com.example.myaiproject.shipping.model.ShippingTrackingEventChange;
import com.example.myaiproject.shipping.model.ShippingTrackingSnapshot;
import com.example.myaiproject.shipping.notify.ShippingTrackingEmailTemplateBuilder;
import com.example.myaiproject.shipping.notify.TrackingNotificationSender;
import com.example.myaiproject.shipping.repo.ShippingTrackingBindingRepository;
import com.example.myaiproject.shipping.repo.ShippingTrackingChangeLogRepository;
import com.example.myaiproject.shipping.repo.ShippingTrackingSnapshotRepository;
import com.example.myaiproject.tool.msc.MscTrackingStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ShippingTrackingService {
    private static final Logger log = LoggerFactory.getLogger(ShippingTrackingService.class);

    private final ShippingTrackingBindingRepository bindingRepository;
    private final ShippingTrackingSnapshotRepository snapshotRepository;
    private final ShippingTrackingChangeLogRepository changeLogRepository;
    private final ShippingTrackingChangeDetector changeDetector;
    private final MscTrackingClient mscTrackingClient;
    private final TrackingNotificationSender notificationSender;
    private final ShippingTrackingEmailTemplateBuilder emailTemplateBuilder;
    private final ObjectMapper objectMapper;

    public ShippingTrackingService(
            ShippingTrackingBindingRepository bindingRepository,
            ShippingTrackingSnapshotRepository snapshotRepository,
            ShippingTrackingChangeLogRepository changeLogRepository,
            ShippingTrackingChangeDetector changeDetector,
            MscTrackingClient mscTrackingClient,
            TrackingNotificationSender notificationSender,
            ShippingTrackingEmailTemplateBuilder emailTemplateBuilder,
            ObjectMapper objectMapper) {
        this.bindingRepository = bindingRepository;
        this.snapshotRepository = snapshotRepository;
        this.changeLogRepository = changeLogRepository;
        this.changeDetector = changeDetector;
        this.mscTrackingClient = mscTrackingClient;
        this.notificationSender = notificationSender;
        this.emailTemplateBuilder = emailTemplateBuilder;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public ShippingTrackingBinding createBinding(String orderNo, String bookingNo) {
        String cleanOrderNo = requireText(orderNo, "订单号不能为空");
        String cleanBookingNo = requireText(bookingNo, "订舱号不能为空");
        OffsetDateTime now = OffsetDateTime.now();
        ShippingTrackingBinding binding = bindingRepository.insert(cleanOrderNo, cleanBookingNo, now);
        queryAndPersist(binding, true, false);
        return bindingRepository.findById(binding.id()).orElseThrow();
    }

    public List<ShippingTrackingBinding> listBindings() {
        return bindingRepository.findAll();
    }

    public ShippingTrackingBinding getBinding(long id) {
        return bindingRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("绑定不存在: " + id));
    }

    @Transactional
    public ShippingTrackingBinding syncBinding(long id) {
        ShippingTrackingBinding binding = getBinding(id);
        queryAndPersist(binding, false, true);
        return bindingRepository.findById(id).orElseThrow();
    }

    @Transactional
    public void disableBinding(long id) {
        getBinding(id);
        bindingRepository.disable(id, OffsetDateTime.now());
    }

    @Transactional
    public void syncBindingForBatch(ShippingTrackingBinding binding) {
        queryAndPersist(binding, false, true);
    }

    private void queryAndPersist(ShippingTrackingBinding binding, boolean baseline, boolean notifyOnChange) {
        OffsetDateTime now = OffsetDateTime.now();
        MscTrackingQueryResult queryResult = querySafely(binding.bookingNo(), now);
        String snapshotStatus = snapshotStatus(queryResult.status());
        ShippingTrackingSnapshot previousSuccess = "SUCCESS".equals(snapshotStatus)
                ? snapshotRepository.findLatestSuccess(binding.id()).orElse(null)
                : null;

        ShippingTrackingSnapshot currentSnapshot = snapshotRepository.insert(
                binding.id(),
                queryResult.queryTime(),
                snapshotStatus,
                queryResult.events(),
                queryResult.rawText(),
                queryResult.eta(),
                queryResult.latestNode(),
                queryResult.screenshotPath(),
                queryResult.errorReason(),
                baseline,
                now);

        bindingRepository.updateAfterQuery(
                binding.id(),
                snapshotStatus,
                queryResult.eta(),
                queryResult.latestNode(),
                queryResult.queryTime(),
                now);

        if (baseline || !notifyOnChange || !"SUCCESS".equals(snapshotStatus) || previousSuccess == null) {
            return;
        }

        List<ShippingTrackingEventChange> changes = changeDetector.detect(
                previousSuccess.events(),
                currentSnapshot.events());
        if (changes.isEmpty()) {
            return;
        }

        ShippingTrackingEmailTemplateBuilder.EmailContent email = emailTemplateBuilder.buildChangeNotification(
                binding,
                queryResult.eta(),
                queryResult.latestNode(),
                changes,
                OffsetDateTime.now());
        boolean emailSent = false;
        OffsetDateTime emailSentTime = null;
        try {
            emailSent = notificationSender.send(email.subject(), email.htmlBody());
            if (emailSent) {
                emailSentTime = OffsetDateTime.now();
            }
        } catch (Exception error) {
            log.warn("Failed to send shipping tracking notification for binding {}.", binding.id(), error);
        }
        OffsetDateTime changeLogTime = OffsetDateTime.now();
        changeLogRepository.insert(
                binding.id(),
                previousSuccess.id(),
                currentSnapshot.id(),
                "EVENTS_CHANGED",
                "检测到 " + changes.size() + " 条物流事件变化",
                toJson(changes.stream().map(ShippingTrackingEventChange::beforeEvent).toList()),
                toJson(changes.stream().map(ShippingTrackingEventChange::afterEvent).toList()),
                emailSent,
                emailSentTime,
                changeLogTime);
    }

    private MscTrackingQueryResult querySafely(String bookingNo, OffsetDateTime fallbackTime) {
        try {
            return mscTrackingClient.queryBooking(bookingNo);
        } catch (Exception error) {
            return new MscTrackingQueryResult(
                    MscTrackingStatus.FAILED,
                    "",
                    "",
                    "",
                    "",
                    List.of(),
                    "",
                    error.getClass().getSimpleName() + ": " + (error.getMessage() == null ? "" : error.getMessage()),
                    fallbackTime);
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception error) {
            throw new IllegalStateException("Failed to serialize change payload.", error);
        }
    }

    private static String snapshotStatus(MscTrackingStatus status) {
        if (status == MscTrackingStatus.SUCCESS || status == MscTrackingStatus.NO_RESULT || status == MscTrackingStatus.MANUAL_REQUIRED) {
            return status.name();
        }
        return "FAILED";
    }

    private static String requireText(String value, String message) {
        if (value == null || value.trim().isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }
}

package com.example.myaiproject.shipping.service;

import com.example.myaiproject.shipping.client.MscTrackingClient;
import com.example.myaiproject.shipping.client.MscTrackingQueryResult;
import com.example.myaiproject.shipping.model.NotificationAccount;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ShippingTrackingService {
    private static final Logger log = LoggerFactory.getLogger(ShippingTrackingService.class);

    private final ShippingTrackingBindingRepository bindingRepository;
    private final ShippingTrackingSnapshotRepository snapshotRepository;
    private final ShippingTrackingChangeLogRepository changeLogRepository;
    private final ShippingTrackingChangeDetector changeDetector;
    private final MscTrackingClient mscTrackingClient;
    private final TrackingNotificationSender notificationSender;
    private final NotificationAccountService notificationAccountService;
    private final ShippingTrackingEmailTemplateBuilder emailTemplateBuilder;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    public ShippingTrackingService(
            ShippingTrackingBindingRepository bindingRepository,
            ShippingTrackingSnapshotRepository snapshotRepository,
            ShippingTrackingChangeLogRepository changeLogRepository,
            ShippingTrackingChangeDetector changeDetector,
            MscTrackingClient mscTrackingClient,
            TrackingNotificationSender notificationSender,
            NotificationAccountService notificationAccountService,
            ShippingTrackingEmailTemplateBuilder emailTemplateBuilder,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager) {
        this.bindingRepository = bindingRepository;
        this.snapshotRepository = snapshotRepository;
        this.changeLogRepository = changeLogRepository;
        this.changeDetector = changeDetector;
        this.mscTrackingClient = mscTrackingClient;
        this.notificationSender = notificationSender;
        this.notificationAccountService = notificationAccountService;
        this.emailTemplateBuilder = emailTemplateBuilder;
        this.objectMapper = objectMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public ShippingTrackingBinding createBinding(String orderNo, String bookingNo) {
        String cleanOrderNo = requireText(orderNo, "订单号不能为空");
        String cleanBookingNo = requireText(bookingNo, "订舱号不能为空");
        bindingRepository.findByOrderNo(cleanOrderNo).ifPresent(existing -> {
            throw new IllegalArgumentException(
                    "订单号 " + cleanOrderNo + " 已绑定订舱号 " + existing.bookingNo() + "，请先停用或更换订单号");
        });
        bindingRepository.findByBookingNo(cleanBookingNo).ifPresent(existing -> {
            throw new IllegalArgumentException(
                    "订舱号 " + cleanBookingNo + " 已绑定订单号 " + existing.orderNo() + "，请先停用或更换订舱号");
        });
        OffsetDateTime now = OffsetDateTime.now();
        ShippingTrackingBinding binding = transactionTemplate.execute(
                status -> bindingRepository.insert(cleanOrderNo, cleanBookingNo, now));
        MscTrackingQueryResult queryResult = querySafely(binding.bookingNo(), OffsetDateTime.now());
        PersistedQuery persistedQuery = persistQueryResultInTransaction(binding, queryResult, true);
        return persistedQuery.binding();
    }

    public List<ShippingTrackingBinding> listBindings() {
        return bindingRepository.findAll();
    }

    public ShippingTrackingBinding getBinding(long id) {
        return bindingRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("绑定不存在: " + id));
    }

    public ShippingTrackingBinding syncBinding(long id) {
        ShippingTrackingBinding binding = getBinding(id);
        return syncBindingRecord(binding, true);
    }

    @Transactional
    public void disableBinding(long id) {
        getBinding(id);
        bindingRepository.disable(id, OffsetDateTime.now());
    }

    public void syncBindingForBatch(ShippingTrackingBinding binding) {
        syncBindingRecord(binding, true);
    }

    private ShippingTrackingBinding syncBindingRecord(ShippingTrackingBinding binding, boolean notifyOnChange) {
        MscTrackingQueryResult queryResult = querySafely(binding.bookingNo(), OffsetDateTime.now());
        PersistedQuery persistedQuery = persistQueryResultInTransaction(binding, queryResult, false);
        if (notifyOnChange && !persistedQuery.changes().isEmpty()) {
            sendNotificationAndRecordChange(binding, queryResult, persistedQuery);
        }
        return persistedQuery.binding();
    }

    private PersistedQuery persistQueryResultInTransaction(
            ShippingTrackingBinding binding,
            MscTrackingQueryResult queryResult,
            boolean baseline) {
        return transactionTemplate.execute(status -> {
            OffsetDateTime now = OffsetDateTime.now();
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

            ShippingTrackingBinding updatedBinding = bindingRepository.findById(binding.id()).orElseThrow();
            List<ShippingTrackingEventChange> changes = shouldDetectChanges(baseline, snapshotStatus, previousSuccess)
                    ? changeDetector.detect(previousSuccess.events(), currentSnapshot.events())
                    : List.of();
            return new PersistedQuery(updatedBinding, previousSuccess, currentSnapshot, changes);
        });
    }

    private void sendNotificationAndRecordChange(
            ShippingTrackingBinding binding,
            MscTrackingQueryResult queryResult,
            PersistedQuery persistedQuery) {
        ShippingTrackingEmailTemplateBuilder.EmailContent email = emailTemplateBuilder.buildChangeNotification(
                binding,
                queryResult.eta(),
                queryResult.latestNode(),
                persistedQuery.changes(),
                OffsetDateTime.now());
        boolean emailSent = false;
        OffsetDateTime emailSentTime = null;
        try {
            List<NotificationAccount> accounts = notificationAccountService.listEnabled();
            if (accounts.isEmpty()) {
                emailSent = notificationSender.send(email.subject(), email.htmlBody());
            } else {
                for (NotificationAccount account : accounts) {
                    boolean ok = notificationSender.sendAs(
                            email.subject(),
                            email.htmlBody(),
                            account.email(),
                            account.smtpPassword());
                    if (ok) {
                        emailSent = true;
                    }
                }
            }
            if (emailSent) {
                emailSentTime = OffsetDateTime.now();
            }
        } catch (Exception error) {
            log.warn("Failed to send shipping tracking notification for binding {}.", binding.id(), error);
        }
        boolean finalEmailSent = emailSent;
        OffsetDateTime finalEmailSentTime = emailSentTime;
        transactionTemplate.executeWithoutResult(status -> changeLogRepository.insert(
                binding.id(),
                persistedQuery.previousSuccess().id(),
                persistedQuery.currentSnapshot().id(),
                "EVENTS_CHANGED",
                "检测到 " + persistedQuery.changes().size() + " 条物流事件变化",
                toJson(persistedQuery.changes().stream().map(ShippingTrackingEventChange::beforeEvent).toList()),
                toJson(persistedQuery.changes().stream().map(ShippingTrackingEventChange::afterEvent).toList()),
                finalEmailSent,
                finalEmailSentTime,
                OffsetDateTime.now()));
    }

    private static boolean shouldDetectChanges(
            boolean baseline,
            String snapshotStatus,
            ShippingTrackingSnapshot previousSuccess) {
        return !baseline && "SUCCESS".equals(snapshotStatus) && previousSuccess != null;
    }

    private record PersistedQuery(
            ShippingTrackingBinding binding,
            ShippingTrackingSnapshot previousSuccess,
            ShippingTrackingSnapshot currentSnapshot,
            List<ShippingTrackingEventChange> changes) {
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

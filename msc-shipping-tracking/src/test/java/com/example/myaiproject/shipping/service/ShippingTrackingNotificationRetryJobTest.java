package com.example.myaiproject.shipping.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.myaiproject.shipping.model.NotificationAccount;
import com.example.myaiproject.shipping.model.ShippingTrackingBinding;
import com.example.myaiproject.shipping.model.ShippingTrackingChangeLog;
import com.example.myaiproject.shipping.notify.ShippingTrackingEmailTemplateBuilder;
import com.example.myaiproject.shipping.notify.TrackingNotificationSender;
import com.example.myaiproject.shipping.repo.ShippingTrackingBindingRepository;
import com.example.myaiproject.shipping.repo.ShippingTrackingChangeLogRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class ShippingTrackingNotificationRetryJobTest {

    @Test
    void successfulSendMarksRowAndIncrementsRetryCount() {
        Fixtures fx = new Fixtures();
        fx.changeLogRepo.pending = List.of(fx.changeLogRow(42L, 1));
        fx.bindingRepo.byId.put(fx.binding.id(), fx.binding);
        fx.sender.sendReturn = true;

        fx.job.runRetryCycle();

        assertEquals(1, fx.sender.sendCalls);
        assertEquals(0, fx.sender.sendAsCalls);
        assertEquals(List.of(42L), fx.changeLogRepo.marked);
        assertEquals(List.of(), fx.changeLogRepo.bumped);
    }

    @Test
    void failedSendOnlyBumpsRetryCount() {
        Fixtures fx = new Fixtures();
        fx.changeLogRepo.pending = List.of(fx.changeLogRow(99L, 0));
        fx.bindingRepo.byId.put(fx.binding.id(), fx.binding);
        fx.sender.sendReturn = false;

        fx.job.runRetryCycle();

        assertEquals(List.of(), fx.changeLogRepo.marked);
        assertEquals(List.of(99L), fx.changeLogRepo.bumped);
    }

    @Test
    void perAccountSendUsesSendAsAndAnySuccessMarksSent() {
        Fixtures fx = new Fixtures();
        fx.changeLogRepo.pending = List.of(fx.changeLogRow(7L, 0));
        fx.bindingRepo.byId.put(fx.binding.id(), fx.binding);

        OffsetDateTime now = OffsetDateTime.now();
        fx.accountService.enabled = List.of(
                new NotificationAccount(1L, "a@example.com", "pwa", true, now, now),
                new NotificationAccount(2L, "b@example.com", "pwb", true, now, now));
        fx.sender.sendAsResults.put("a@example.com", false);
        fx.sender.sendAsResults.put("b@example.com", true);

        fx.job.runRetryCycle();

        assertEquals(2, fx.sender.sendAsCalls);
        assertEquals(0, fx.sender.sendCalls);
        assertEquals(List.of(7L), fx.changeLogRepo.marked);
    }

    @Test
    void rowWithoutKnownBindingIsSkippedSafely() {
        Fixtures fx = new Fixtures();
        fx.changeLogRepo.pending = List.of(fx.changeLogRow(11L, 1));
        // Note: no binding registered

        fx.job.runRetryCycle();  // must not throw

        assertEquals(0, fx.sender.sendCalls);
        assertEquals(0, fx.sender.sendAsCalls);
        assertEquals(List.of(), fx.changeLogRepo.marked);
        assertEquals(List.of(), fx.changeLogRepo.bumped);
    }

    /** Bag of test doubles + a wired job. */
    private static class Fixtures {
        final FakeChangeLogRepo changeLogRepo = new FakeChangeLogRepo();
        final FakeBindingRepo bindingRepo = new FakeBindingRepo();
        final FakeSender sender = new FakeSender();
        final FakeAccountService accountService = new FakeAccountService();
        final ShippingTrackingEmailTemplateBuilder templateBuilder = new ShippingTrackingEmailTemplateBuilder();
        final ObjectMapper objectMapper = new ObjectMapper();
        final ShippingTrackingProperties properties = new ShippingTrackingProperties();
        final ShippingTrackingBinding binding;
        final ShippingTrackingNotificationRetryJob job;

        Fixtures() {
            properties.setRetryMaxAttempts(6);
            properties.setRetryMaxAgeHours(24);
            OffsetDateTime now = OffsetDateTime.now();
            binding = new ShippingTrackingBinding(
                    500L, "ORDER-500", "BOOKING-500", "MSC", true,
                    "SUCCESS", "2026-06-01", "Loaded at port", null, null, now, now);
            assertNotNull(binding);
            job = new ShippingTrackingNotificationRetryJob(
                    changeLogRepo, bindingRepo, sender, accountService, templateBuilder,
                    objectMapper, properties);
        }

        ShippingTrackingChangeLog changeLogRow(long id, int retryCount) {
            return new ShippingTrackingChangeLog(
                    id, binding.id(), 1L, 2L,
                    "EVENTS_CHANGED",
                    "1 条变化",
                    "[]",
                    "[]",
                    false,
                    null,
                    retryCount,
                    null,
                    OffsetDateTime.now().minusHours(1));
        }
    }

    private static class FakeChangeLogRepo extends ShippingTrackingChangeLogRepository {
        List<ShippingTrackingChangeLog> pending = List.of();
        final List<Long> marked = new ArrayList<>();
        final List<Long> bumped = new ArrayList<>();

        FakeChangeLogRepo() {
            super(new JdbcTemplate());
        }

        @Override
        public List<ShippingTrackingChangeLog> findPendingRetries(int maxAttempts, OffsetDateTime ageCutoff) {
            return pending;
        }

        @Override
        public void markEmailSent(long id, OffsetDateTime sentAt) {
            marked.add(id);
        }

        @Override
        public void bumpRetryCount(long id, OffsetDateTime attemptAt) {
            bumped.add(id);
        }
    }

    private static class FakeBindingRepo extends ShippingTrackingBindingRepository {
        final Map<Long, ShippingTrackingBinding> byId = new HashMap<>();

        FakeBindingRepo() {
            super(new JdbcTemplate());
        }

        @Override
        public Optional<ShippingTrackingBinding> findById(long id) {
            return Optional.ofNullable(byId.get(id));
        }
    }

    private static class FakeSender implements TrackingNotificationSender {
        int sendCalls = 0;
        int sendAsCalls = 0;
        boolean sendReturn = false;
        final Map<String, Boolean> sendAsResults = new HashMap<>();

        @Override
        public boolean send(String subject, String body) {
            sendCalls++;
            return sendReturn;
        }

        @Override
        public boolean sendAs(String subject, String body, String email, String smtpPassword) {
            sendAsCalls++;
            return sendAsResults.getOrDefault(email, false);
        }
    }

    private static class FakeAccountService extends NotificationAccountService {
        List<NotificationAccount> enabled = List.of();

        FakeAccountService() {
            super(null);
        }

        @Override
        public List<NotificationAccount> listEnabled() {
            return enabled;
        }
    }

}

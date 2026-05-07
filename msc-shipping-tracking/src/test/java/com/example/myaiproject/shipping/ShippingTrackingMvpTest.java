package com.example.myaiproject.shipping;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.example.myaiproject.shipping.client.MscTrackingClient;
import com.example.myaiproject.shipping.client.MscTrackingQueryResult;
import com.example.myaiproject.shipping.model.ShippingTrackingBinding;
import com.example.myaiproject.shipping.model.ShippingTrackingEvent;
import com.example.myaiproject.shipping.notify.TrackingNotificationSender;
import com.example.myaiproject.shipping.service.ShippingTrackingScheduler;
import com.example.myaiproject.shipping.service.ShippingTrackingService;
import com.example.myaiproject.tool.msc.MscTrackingStatus;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ShippingTrackingMvpTest {
    @Autowired
    ShippingTrackingService service;
    @Autowired
    ShippingTrackingScheduler scheduler;
    @Autowired
    JdbcTemplate jdbcTemplate;
    @Autowired
    FakeMscTrackingClient fakeClient;
    @Autowired
    RecordingNotificationSender notificationSender;
    @Autowired
    MockMvc mockMvc;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("delete from shipping_tracking_change_log");
        jdbcTemplate.update("delete from shipping_tracking_snapshot");
        jdbcTemplate.update("delete from shipping_tracking_binding");
        fakeClient.reset();
        notificationSender.reset();
    }

    @Test
    void createBindingImmediatelyCreatesBaselineSnapshotWithoutEmail() {
        fakeClient.enqueue(success(List.of(event("18/05/2026", "Ningbo, CN", "Full Intended Transshipment", "MSC A"))));

        ShippingTrackingBinding binding = service.createBinding("ORD-001", "177C1234498");

        assertNotNull(binding.id());
        assertEquals("ORD-001", binding.orderNo());
        assertEquals("177C1234498", binding.bookingNo());
        assertEquals(1, count("shipping_tracking_binding"));
        assertEquals(1, count("shipping_tracking_snapshot"));
        assertEquals(1, countWhere("shipping_tracking_snapshot", "baseline = true"));
        assertEquals(0, count("shipping_tracking_change_log"));
        assertEquals(0, notificationSender.sentMessages.size());
    }

    @Test
    void manualSyncWithNoEventChangesSavesSnapshotButDoesNotSendEmail() {
        List<ShippingTrackingEvent> baseline = List.of(event("18/05/2026", "Ningbo, CN", "Full Intended Transshipment", "MSC A"));
        fakeClient.enqueue(success(baseline));
        ShippingTrackingBinding binding = service.createBinding("ORD-002", "177C1234498");
        fakeClient.enqueue(success(baseline));

        service.syncBinding(binding.id());

        assertEquals(2, count("shipping_tracking_snapshot"));
        assertEquals(0, count("shipping_tracking_change_log"));
        assertEquals(0, notificationSender.sentMessages.size());
    }

    @Test
    void manualSyncWithEventChangesCreatesChangeLogAndSendsEmail() {
        fakeClient.enqueue(success(List.of(event("18/05/2026", "Ningbo, CN", "Estimated Time of Arrival", "MSC A"))));
        ShippingTrackingBinding binding = service.createBinding("ORD-003", "177C1234498");
        fakeClient.enqueue(success(List.of(event("19/05/2026", "Ningbo, CN", "Estimated Time of Arrival", "MSC A"))));

        service.syncBinding(binding.id());

        assertEquals(2, count("shipping_tracking_snapshot"));
        assertEquals(1, count("shipping_tracking_change_log"));
        assertEquals(1, notificationSender.sentMessages.size());
        assertTrue(notificationSender.sentMessages.get(0).subject().contains("MSC货物追踪变更通知｜订单号 ORD-003｜1 条变化"));
        assertTrue(notificationSender.sentMessages.get(0).body().contains("结论先行"));
        assertTrue(notificationSender.sentMessages.get(0).body().contains("本次一句话结论"));
        assertTrue(notificationSender.sentMessages.get(0).body().contains("变化明细"));
        assertTrue(notificationSender.sentMessages.get(0).body().contains("19/05/2026"));
    }

    @Test
    void changeLogRecordsEmailNotSentWhenSenderReturnsFalse() {
        notificationSender.nextSendResult = false;
        fakeClient.enqueue(success(List.of(event("18/05/2026", "Ningbo, CN", "Loaded", "MSC A"))));
        ShippingTrackingBinding binding = service.createBinding("ORD-MAIL", "177C1234498");
        fakeClient.enqueue(success(List.of(event("19/05/2026", "Ningbo, CN", "Loaded", "MSC A"))));

        service.syncBinding(binding.id());

        assertEquals(1, count("shipping_tracking_change_log"));
        assertFalse(jdbcTemplate.queryForObject(
                "select email_sent from shipping_tracking_change_log",
                Boolean.class));
        assertNull(jdbcTemplate.queryForObject(
                "select email_sent_time from shipping_tracking_change_log",
                OffsetDateTime.class));
    }

    @Test
    void etdDateChangeCreatesOnlyOneChangeLogEntry() {
        fakeClient.enqueue(success(List.of(
                event("18/05/2026", "Ningbo, CN", "Estimated Time of Departure", "MSC A"),
                event("22/05/2026", "Singapore, SG", "Loaded", "MSC A"))));
        ShippingTrackingBinding binding = service.createBinding("ORD-ETD", "177C1234498");
        fakeClient.enqueue(success(List.of(
                event("19/05/2026", "Ningbo, CN", "Estimated Time of Departure", "MSC A"),
                event("22/05/2026", "Singapore, SG", "Loaded", "MSC A"))));

        service.syncBinding(binding.id());

        assertEquals("检测到 1 条物流事件变化", singleChangeSummary());
        assertEquals(1, notificationSender.sentMessages.size());
        assertTrue(notificationSender.sentMessages.get(0).body().contains("预计开船时间变更"));
        assertTrue(notificationSender.sentMessages.get(0).body().contains("请关注是否需要同步客户交付预期"));
    }

    @Test
    void estimatedDepartureBecomingActualDepartureDoesNotCreateChangeLogOrEmail() {
        fakeClient.enqueue(success(List.of(
                event("07/05/2026", "Qingdao, CN", "Estimated Time of Departure", "MSC MELANI III SE619A"))));
        ShippingTrackingBinding binding = service.createBinding("ORD-DEPARTED", "177C1234498");
        fakeClient.enqueue(success(List.of(
                event("08/05/2026", "Qingdao, CN", "Actual Time of Departure", "MSC MELANI III SE619A"))));

        service.syncBinding(binding.id());

        assertEquals(2, count("shipping_tracking_snapshot"));
        assertEquals(0, count("shipping_tracking_change_log"));
        assertEquals(0, notificationSender.sentMessages.size());
    }

    @Test
    void etaDateChangeCreatesOnlyOneChangeLogEntry() {
        fakeClient.enqueue(success(List.of(
                event("18/05/2026", "Ningbo, CN", "Loaded", "MSC A"),
                event("25/05/2026", "Los Angeles, US", "Estimated Time of Arrival", "MSC A"))));
        ShippingTrackingBinding binding = service.createBinding("ORD-ETA", "177C1234498");
        fakeClient.enqueue(success(List.of(
                event("18/05/2026", "Ningbo, CN", "Loaded", "MSC A"),
                event("26/05/2026", "Los Angeles, US", "Estimated Time of Arrival", "MSC A"))));

        service.syncBinding(binding.id());

        assertEquals("检测到 1 条物流事件变化", singleChangeSummary());
    }

    @Test
    void insertingOneEventDoesNotAmplifyFollowingRowsIntoChanges() {
        ShippingTrackingEvent first = event("18/05/2026", "Ningbo, CN", "Loaded", "MSC A");
        ShippingTrackingEvent second = event("25/05/2026", "Los Angeles, US", "Estimated Time of Arrival", "MSC A");
        ShippingTrackingEvent inserted = event("20/05/2026", "Busan, KR", "Transshipment", "MSC B");
        fakeClient.enqueue(success(List.of(first, second)));
        ShippingTrackingBinding binding = service.createBinding("ORD-ADD", "177C1234498");
        fakeClient.enqueue(success(List.of(first, inserted, second)));

        service.syncBinding(binding.id());

        assertEquals("检测到 1 条物流事件变化", singleChangeSummary());
    }

    @Test
    void deletingOneEventDoesNotAmplifyFollowingRowsIntoChanges() {
        ShippingTrackingEvent first = event("18/05/2026", "Ningbo, CN", "Loaded", "MSC A");
        ShippingTrackingEvent removed = event("20/05/2026", "Busan, KR", "Transshipment", "MSC B");
        ShippingTrackingEvent second = event("25/05/2026", "Los Angeles, US", "Estimated Time of Arrival", "MSC A");
        fakeClient.enqueue(success(List.of(first, removed, second)));
        ShippingTrackingBinding binding = service.createBinding("ORD-DEL", "177C1234498");
        fakeClient.enqueue(success(List.of(first, second)));

        service.syncBinding(binding.id());

        assertEquals("检测到 1 条物流事件变化", singleChangeSummary());
    }

    @Test
    void noResultSnapshotDoesNotCreateChangeLogOrEmail() {
        fakeClient.enqueue(success(List.of(event("18/05/2026", "Ningbo, CN", "Loaded", "MSC A"))));
        ShippingTrackingBinding binding = service.createBinding("ORD-004", "177C1234498");
        fakeClient.enqueue(noResult());

        service.syncBinding(binding.id());

        assertEquals(2, count("shipping_tracking_snapshot"));
        assertEquals("NO_RESULT", jdbcTemplate.queryForObject(
                "select last_status from shipping_tracking_binding where id = ?",
                String.class,
                binding.id()));
        assertEquals(0, count("shipping_tracking_change_log"));
        assertEquals(0, notificationSender.sentMessages.size());
    }

    @Test
    void scheduledJobContinuesWhenOneBindingFails() {
        fakeClient.enqueue(success(List.of(event("18/05/2026", "Ningbo, CN", "Loaded", "MSC A"))));
        ShippingTrackingBinding first = service.createBinding("ORD-005", "177C1234498");
        fakeClient.enqueue(success(List.of(event("18/05/2026", "Ningbo, CN", "Loaded", "MSC A"))));
        ShippingTrackingBinding second = service.createBinding("ORD-006", "177C9999498");

        fakeClient.enqueueFailure(new IllegalStateException("browser failed"));
        fakeClient.enqueue(success(List.of(event("19/05/2026", "Ningbo, CN", "Loaded", "MSC A"))));

        scheduler.runDailyBatch();

        assertTrue(first.enabled());
        assertEquals("FAILED", jdbcTemplate.queryForObject(
                "select last_status from shipping_tracking_binding where id = ?",
                String.class,
                first.id()));
        assertEquals(2, countWhere("shipping_tracking_snapshot", "binding_id = " + second.id()));
        assertEquals(1, notificationSender.sentMessages.size());
        assertTrue(notificationSender.sentMessages.get(0).subject().contains("MSC货物追踪变更通知｜订单号 ORD-006｜1 条变化"));
    }

    @Test
    void restEndpointsCoverBindingLifecycle() throws Exception {
        List<ShippingTrackingEvent> baseline = List.of(event("18/05/2026", "Ningbo, CN", "Loaded", "MSC A"));
        fakeClient.enqueue(success(baseline));

        MvcResult created = mockMvc.perform(post("/api/shipping-tracking/bindings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderNo\":\"ORD-API\",\"bookingNo\":\"177CAPI001\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderNo").value("ORD-API"))
                .andExpect(jsonPath("$.bookingNo").value("177CAPI001"))
                .andExpect(jsonPath("$.lastStatus").value("SUCCESS"))
                .andReturn();
        Number id = JsonPath.read(created.getResponse().getContentAsString(), "$.id");

        mockMvc.perform(get("/api/shipping-tracking/bindings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
        mockMvc.perform(get("/api/shipping-tracking/bindings/{id}", id.longValue()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.intValue()));

        fakeClient.enqueue(success(baseline));
        mockMvc.perform(post("/api/shipping-tracking/bindings/{id}/sync", id.longValue()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lastStatus").value("SUCCESS"));
        mockMvc.perform(post("/api/shipping-tracking/bindings/{id}/disable", id.longValue()))
                .andExpect(status().isNoContent());

        assertFalse(jdbcTemplate.queryForObject(
                "select enabled from shipping_tracking_binding where id = ?",
                Boolean.class,
                id.longValue()));
    }

    @Test
    void pageContainsSyncAndDisableButtons() throws Exception {
        MvcResult result = mockMvc.perform(get("/shipping-tracking.html"))
                .andExpect(status().isOk())
                .andReturn();
        String html = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(html.contains("手动同步"));
        assertTrue(html.contains("停用"));
    }

    @Test
    void testEmailEndpointReturnsSenderResultWithoutMscQuery() throws Exception {
        notificationSender.nextSendResult = true;

        mockMvc.perform(post("/api/shipping-tracking/test-email"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        assertEquals(1, notificationSender.sentMessages.size());
        assertEquals("MSC物流提醒测试邮件", notificationSender.sentMessages.get(0).subject());
        assertTrue(notificationSender.sentMessages.get(0).body().contains("QQ 邮箱 SMTP 配置成功"));
        assertEquals(0, fakeClient.queryCount);
    }

    @Test
    void testEmailEndpointReturnsFalseWhenSenderReturnsFalse() throws Exception {
        notificationSender.nextSendResult = false;

        mockMvc.perform(post("/api/shipping-tracking/test-email"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false));

        assertEquals(1, notificationSender.sentMessages.size());
        assertEquals(0, fakeClient.queryCount);
    }

    private int count(String table) {
        return jdbcTemplate.queryForObject("select count(*) from " + table, Integer.class);
    }

    private int countWhere(String table, String whereClause) {
        return jdbcTemplate.queryForObject("select count(*) from " + table + " where " + whereClause, Integer.class);
    }

    private String singleChangeSummary() {
        assertEquals(1, count("shipping_tracking_change_log"));
        return jdbcTemplate.queryForObject(
                "select change_summary from shipping_tracking_change_log",
                String.class);
    }

    private static ShippingTrackingEvent event(String date, String location, String description, String vesselVoyage) {
        return new ShippingTrackingEvent(date, location, description, vesselVoyage);
    }

    private static MscTrackingQueryResult success(List<ShippingTrackingEvent> events) {
        return new MscTrackingQueryResult(
                MscTrackingStatus.SUCCESS,
                "raw text",
                "",
                "22/06/2026",
                events.isEmpty() ? "" : events.get(0).location(),
                events,
                "/tmp/screenshot.png",
                "",
                OffsetDateTime.now());
    }

    private static MscTrackingQueryResult noResult() {
        return new MscTrackingQueryResult(
                MscTrackingStatus.NO_RESULT,
                "未找到与该订舱号匹配的结果。",
                "",
                "",
                "",
                List.of(),
                "/tmp/no-result.png",
                "MSC page returned no visible result for this booking number.",
                OffsetDateTime.now());
    }

    @TestConfiguration
    static class TestConfig {
        @Bean
        @Primary
        FakeMscTrackingClient fakeMscTrackingClient() {
            return new FakeMscTrackingClient();
        }

        @Bean
        @Primary
        RecordingNotificationSender recordingNotificationSender() {
            return new RecordingNotificationSender();
        }
    }

    static class FakeMscTrackingClient implements MscTrackingClient {
        private final Queue<Object> outcomes = new ArrayDeque<>();
        int queryCount;

        void enqueue(MscTrackingQueryResult result) {
            outcomes.add(result);
        }

        void enqueueFailure(RuntimeException error) {
            outcomes.add(error);
        }

        void reset() {
            outcomes.clear();
            queryCount = 0;
        }

        @Override
        public MscTrackingQueryResult queryBooking(String bookingNo) {
            queryCount++;
            Object outcome = outcomes.remove();
            if (outcome instanceof RuntimeException error) {
                throw error;
            }
            return (MscTrackingQueryResult) outcome;
        }
    }

    static class RecordingNotificationSender implements TrackingNotificationSender {
        final List<SentMessage> sentMessages = new java.util.ArrayList<>();
        boolean nextSendResult = true;

        void reset() {
            sentMessages.clear();
            nextSendResult = true;
        }

        @Override
        public boolean send(String subject, String body) {
            sentMessages.add(new SentMessage(subject, body));
            return nextSendResult;
        }
    }

    record SentMessage(String subject, String body) {
    }
}

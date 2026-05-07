package com.example.myaiproject.shipping;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.myaiproject.shipping.model.ShippingTrackingBinding;
import com.example.myaiproject.shipping.model.ShippingTrackingEvent;
import com.example.myaiproject.shipping.model.ShippingTrackingEventChange;
import com.example.myaiproject.shipping.notify.ShippingTrackingEmailTemplateBuilder;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class ShippingTrackingEmailTemplateBuilderTest {
    private final ShippingTrackingEmailTemplateBuilder builder = new ShippingTrackingEmailTemplateBuilder();

    @Test
    void buildsDashboardStyleHtmlChangeNotification() {
        ShippingTrackingBinding binding = new ShippingTrackingBinding(
                3L,
                "TEST-ORDER-002",
                "BOOKING-TEST-001",
                "MSC",
                true,
                "SUCCESS",
                "31/05/2026",
                "QINGDAO, CN",
                OffsetDateTime.parse("2026-05-07T09:30:00+08:00"),
                OffsetDateTime.parse("2026-05-07T09:00:00+08:00"),
                OffsetDateTime.parse("2026-05-07T09:30:00+08:00"));
        List<ShippingTrackingEventChange> changes = List.of(new ShippingTrackingEventChange(
                1,
                "日期变化",
                event("07/05/2026"),
                event("08/05/2026")));

        ShippingTrackingEmailTemplateBuilder.EmailContent email = builder.buildChangeNotification(
                binding,
                "31/05/2026",
                "QINGDAO, CN",
                changes,
                OffsetDateTime.parse("2026-05-07T09:40:00+08:00"));

        assertTrue(email.subject().contains("MSC货物追踪变更通知｜订单号 TEST-ORDER-002｜1 条变化"));
        String html = email.htmlBody();
        assertContains(html,
                "MSC货物追踪变更通知",
                "结论先行",
                "本次一句话结论",
                "当前概览",
                "变化明细",
                "后续建议",
                "TEST-ORDER-002",
                "BOOKING-TEST-001",
                "07/05/2026",
                "08/05/2026",
                "Qingdao, CN",
                "Estimated Time of Departure",
                "MSC MELANI III SE619A",
                "空载/满载/船舶/航次",
                "预计开船时间变更",
                "请关注是否需要同步客户交付预期",
                "<table",
                "本邮件仅在系统检测到 MSC 货物追踪信息变化时发送");
    }

    private static ShippingTrackingEvent event(String date) {
        return new ShippingTrackingEvent(
                date,
                "Qingdao, CN",
                "Estimated Time of Departure",
                "MSC MELANI III SE619A");
    }

    private static void assertContains(String text, String... expectedValues) {
        for (String expected : expectedValues) {
            assertTrue(text.contains(expected), () -> "Expected HTML to contain: " + expected);
        }
    }
}

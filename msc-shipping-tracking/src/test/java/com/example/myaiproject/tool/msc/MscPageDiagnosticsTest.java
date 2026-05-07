package com.example.myaiproject.tool.msc;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class MscPageDiagnosticsTest {
    @Test
    void detectsAccessDeniedPageText() {
        assertTrue(MscPageDiagnostics.isAccessDenied("Access Denied\nReference #18"));
        assertTrue(MscPageDiagnostics.isAccessDenied("HTTP/2 403 Forbidden"));
        assertFalse(MscPageDiagnostics.isAccessDenied("货物追踪 订舱号 集装箱号/提单号"));
    }

    @Test
    void detectsTrackingPageReadinessKeywords() {
        assertTrue(MscPageDiagnostics.hasTrackingPageKeyword("货物追踪 集装箱号/提单号 订舱号"));
        assertTrue(MscPageDiagnostics.hasTrackingPageKeyword("请输入订舱号后查询"));
        assertFalse(MscPageDiagnostics.hasTrackingPageKeyword("Access Denied"));
    }

    @Test
    void buildsSanitizedErrorReasonWithPageSummary() {
        String rawBookingNo = "BOOKING-SECRET-001";
        String body = "货物追踪 " + rawBookingNo + " " + "x".repeat(2_000);
        MscPageDiagnostics.PageState state = new MscPageDiagnostics.PageState(
                "https://www.msccargo.cn/zh/track-a-shipment",
                "远程货物追踪-远程货物监控-MSC中国官网 | MSC",
                body);

        String reason = MscPageDiagnostics.buildErrorReason(
                "页面打开但查询输入框未找到",
                state,
                new RuntimeException("Timeout 60000ms exceeded"),
                List.of(rawBookingNo));

        assertTrue(reason.contains("页面打开但查询输入框未找到"));
        assertTrue(reason.contains("currentUrl=https://www.msccargo.cn/zh/track-a-shipment"));
        assertTrue(reason.contains("title=远程货物追踪-远程货物监控-MSC中国官网 | MSC"));
        assertTrue(reason.contains("Timeout 60000ms exceeded"));
        assertTrue(reason.contains("bodyText="));
        assertFalse(reason.contains(rawBookingNo));
        assertTrue(reason.length() < 1_700);
    }
}

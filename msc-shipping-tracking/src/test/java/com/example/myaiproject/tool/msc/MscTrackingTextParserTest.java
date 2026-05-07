package com.example.myaiproject.tool.msc;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class MscTrackingTextParserTest {

    @Test
    void parsesStatusEtaAndLatestNodeFromVisibleText() {
        MscTrackingParsedFields fields = new MscTrackingTextParser().parse("""
                Cargo tracking
                Current Status
                In Transit
                ETA
                2026-05-20
                Latest Event
                Ningbo, China - Loaded on vessel
                """);

        assertEquals("In Transit", fields.parsedCurrentStatus());
        assertEquals("2026-05-20", fields.parsedEta());
        assertEquals("Ningbo, China - Loaded on vessel", fields.parsedLatestNode());
    }

    @Test
    void returnsEmptyFieldsWhenTextDoesNotContainRecognizedLabels() {
        MscTrackingParsedFields fields = new MscTrackingTextParser().parse("No matching shipment was found.");

        assertEquals("", fields.parsedCurrentStatus());
        assertEquals("", fields.parsedEta());
        assertEquals("", fields.parsedLatestNode());
    }

    @Test
    void doesNotTreatSubscriptionCopyAsCurrentStatus() {
        MscTrackingParsedFields fields = new MscTrackingTextParser().parse("""
                最新动向
                NINGBO, CN
                卸货港预计到达时间
                22/06/2026
                订阅追踪与追溯通知
                在收件箱中获取有关货物状态的更新信息。
                立即订阅！
                """);

        assertEquals("", fields.parsedCurrentStatus());
        assertEquals("22/06/2026", fields.parsedEta());
        assertEquals("NINGBO, CN", fields.parsedLatestNode());
    }
}

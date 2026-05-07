package com.example.myaiproject.tool.msc;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class MscTrackingNumberClassifierTest {

    @Test
    void treats177PrefixAsBookingNumber() {
        assertEquals(MscTrackingQueryType.BOOKING, MscTrackingNumberClassifier.classify("177A123405V"));
        assertEquals("订舱号", MscTrackingNumberClassifier.classify("177C1234498").chineseLabel());
    }

    @Test
    void treatsOtherNumbersAsContainerOrBillOfLading() {
        assertEquals(MscTrackingQueryType.CONTAINER_OR_BOL, MscTrackingNumberClassifier.classify("140601157270"));
        assertEquals("集装箱号/提单号", MscTrackingNumberClassifier.classify("270123456926").chineseLabel());
    }

    @Test
    void filtersByConfiguredPrefixBeforeQuerying() {
        List<String> filtered = MscTrackingNumberClassifier.filterByPrefix(
                List.of("140601157270", "177A123405V", "270123456926", "177C1234498"),
                "177",
                5);

        assertEquals(List.of("177A123405V", "177C1234498"), filtered);
    }
}

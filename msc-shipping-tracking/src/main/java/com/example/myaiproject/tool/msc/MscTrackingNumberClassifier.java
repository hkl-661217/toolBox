package com.example.myaiproject.tool.msc;

import java.util.List;

public final class MscTrackingNumberClassifier {
    private static final String BOOKING_PREFIX = "177";

    private MscTrackingNumberClassifier() {
    }

    public static MscTrackingQueryType classify(String trackingNo) {
        if (trackingNo != null && trackingNo.trim().startsWith(BOOKING_PREFIX)) {
            return MscTrackingQueryType.BOOKING;
        }
        return MscTrackingQueryType.CONTAINER_OR_BOL;
    }

    public static List<String> filterByPrefix(List<String> trackingNumbers, String prefix, int maxItems) {
        if (maxItems <= 0 || trackingNumbers == null || trackingNumbers.isEmpty()) {
            return List.of();
        }
        String normalizedPrefix = prefix == null ? "" : prefix.trim();
        return trackingNumbers.stream()
                .filter(number -> normalizedPrefix.isBlank() || number.startsWith(normalizedPrefix))
                .limit(maxItems)
                .toList();
    }
}

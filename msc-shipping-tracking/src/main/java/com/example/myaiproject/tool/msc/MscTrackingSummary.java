package com.example.myaiproject.tool.msc;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public record MscTrackingSummary(
        int total,
        int success,
        int failed,
        Map<MscTrackingStatus, Integer> byStatus) {

    public static MscTrackingSummary from(List<MscTrackingResult> results) {
        Map<MscTrackingStatus, Integer> byStatus = new EnumMap<>(MscTrackingStatus.class);
        for (MscTrackingStatus status : MscTrackingStatus.values()) {
            byStatus.put(status, 0);
        }
        int successCount = 0;
        for (MscTrackingResult result : results) {
            byStatus.computeIfPresent(result.status(), (ignored, count) -> count + 1);
            if (result.success()) {
                successCount++;
            }
        }
        return new MscTrackingSummary(
                results.size(),
                successCount,
                results.size() - successCount,
                byStatus);
    }
}

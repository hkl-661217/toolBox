package com.example.myaiproject.tool.msc;

import java.util.List;

public record MscTrackingOutput(
        String sourcePage,
        String inputFile,
        int maxItems,
        String generatedAt,
        MscTrackingSummary summary,
        List<MscTrackingResult> results) {
}

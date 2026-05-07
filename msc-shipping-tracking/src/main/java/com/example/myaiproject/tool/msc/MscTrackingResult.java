package com.example.myaiproject.tool.msc;

public record MscTrackingResult(
        String trackingNoMasked,
        String queryType,
        boolean success,
        MscTrackingStatus status,
        String rawText,
        String parsedCurrentStatus,
        String parsedEta,
        String parsedLatestNode,
        String screenshotPath,
        String errorReason,
        String queriedAt) {

    public static MscTrackingResult of(
            String trackingNoMasked,
            MscTrackingQueryType queryType,
            MscTrackingStatus status,
            String rawText,
            MscTrackingParsedFields parsedFields,
            String screenshotPath,
            String errorReason,
            String queriedAt) {
        return new MscTrackingResult(
                trackingNoMasked,
                queryType == null ? "" : queryType.chineseLabel(),
                status == MscTrackingStatus.SUCCESS,
                status,
                rawText == null ? "" : rawText,
                parsedFields == null ? "" : parsedFields.parsedCurrentStatus(),
                parsedFields == null ? "" : parsedFields.parsedEta(),
                parsedFields == null ? "" : parsedFields.parsedLatestNode(),
                screenshotPath == null ? "" : screenshotPath,
                errorReason == null ? "" : errorReason,
                queriedAt);
    }
}

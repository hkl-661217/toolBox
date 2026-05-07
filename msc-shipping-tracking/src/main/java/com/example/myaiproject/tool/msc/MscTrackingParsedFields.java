package com.example.myaiproject.tool.msc;

public record MscTrackingParsedFields(
        String parsedCurrentStatus,
        String parsedEta,
        String parsedLatestNode) {

    public static MscTrackingParsedFields empty() {
        return new MscTrackingParsedFields("", "", "");
    }

    public boolean hasAnyValue() {
        return !parsedCurrentStatus.isBlank() || !parsedEta.isBlank() || !parsedLatestNode.isBlank();
    }
}

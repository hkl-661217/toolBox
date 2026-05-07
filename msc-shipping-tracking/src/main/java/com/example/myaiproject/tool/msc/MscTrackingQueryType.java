package com.example.myaiproject.tool.msc;

public enum MscTrackingQueryType {
    CONTAINER_OR_BOL("集装箱号/提单号"),
    BOOKING("订舱号");

    private final String chineseLabel;

    MscTrackingQueryType(String chineseLabel) {
        this.chineseLabel = chineseLabel;
    }

    public String chineseLabel() {
        return chineseLabel;
    }
}

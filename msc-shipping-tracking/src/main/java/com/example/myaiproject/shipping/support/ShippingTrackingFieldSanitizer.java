package com.example.myaiproject.shipping.support;

public final class ShippingTrackingFieldSanitizer {
    public static final int STATUS_MAX_LENGTH = 32;
    public static final int ETA_MAX_LENGTH = 64;
    public static final int LATEST_NODE_MAX_LENGTH = 255;
    public static final int SCREENSHOT_PATH_MAX_LENGTH = 500;
    public static final int ERROR_REASON_MAX_LENGTH = 2000;

    private ShippingTrackingFieldSanitizer() {
    }

    public static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}

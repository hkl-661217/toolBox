package com.example.myaiproject.tool.msc;

import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MscTrackingSanitizer {
    private static final Pattern DETECTED_SHIPMENT_TOKEN = Pattern.compile("\\b[A-Z]{4}(?=[A-Z0-9]*\\d)[A-Z0-9]{6,12}\\b");

    private MscTrackingSanitizer() {
    }

    public static String mask(String trackingNo) {
        if (trackingNo == null) {
            return "****";
        }
        String trimmed = trackingNo.trim();
        if (trimmed.length() <= 7) {
            return "****";
        }
        return trimmed.substring(0, 4) + "****" + trimmed.substring(trimmed.length() - 3);
    }

    public static String sanitize(String text, List<String> rawTrackingNumbers) {
        if (text == null || text.isBlank()) {
            return text == null ? "" : text;
        }

        String sanitized = text;
        if (rawTrackingNumbers != null && !rawTrackingNumbers.isEmpty()) {
            List<String> ordered = rawTrackingNumbers.stream()
                    .filter(number -> number != null && !number.isBlank())
                    .sorted(Comparator.comparingInt(String::length).reversed())
                    .toList();

            for (String trackingNo : ordered) {
                Pattern pattern = Pattern.compile(Pattern.quote(trackingNo), Pattern.CASE_INSENSITIVE);
                Matcher matcher = pattern.matcher(sanitized);
                sanitized = matcher.replaceAll(Matcher.quoteReplacement(mask(trackingNo)));
            }
        }
        return maskDetectedShipmentTokens(sanitized);
    }

    private static String maskDetectedShipmentTokens(String text) {
        Matcher matcher = DETECTED_SHIPMENT_TOKEN.matcher(text);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(result, Matcher.quoteReplacement(mask(matcher.group())));
        }
        matcher.appendTail(result);
        return result.toString();
    }
}

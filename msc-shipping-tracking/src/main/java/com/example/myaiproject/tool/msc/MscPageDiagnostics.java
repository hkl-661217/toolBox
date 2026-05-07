package com.example.myaiproject.tool.msc;

import java.util.List;
import java.util.Locale;

final class MscPageDiagnostics {
    static final int BODY_TEXT_SUMMARY_MAX_LENGTH = 1_000;
    static final int ERROR_REASON_MAX_LENGTH = 1_600;

    private MscPageDiagnostics() {
    }

    static boolean isAccessDenied(String text) {
        String normalized = normalize(text).toLowerCase(Locale.ROOT);
        return normalized.contains("access denied")
                || normalized.contains("403 forbidden")
                || normalized.contains("http 403")
                || normalized.contains("http/2 403")
                || normalized.contains("http/1.1 403");
    }

    static boolean hasTrackingPageKeyword(String text) {
        String normalized = normalize(text);
        return normalized.contains("货物追踪")
                || normalized.contains("订舱号")
                || normalized.contains("输入订舱号")
                || normalized.contains("集装箱号/提单号");
    }

    static boolean isPageUsable(PageState state) {
        if (state == null) {
            return false;
        }
        return !normalize(state.title()).isBlank() || hasTrackingPageKeyword(state.bodyText());
    }

    static boolean hasAnyPageSignal(PageState state) {
        if (state == null) {
            return false;
        }
        return !normalize(state.title()).isBlank()
                || !normalize(state.bodyText()).isBlank();
    }

    static String buildErrorReason(
            String summary,
            PageState state,
            Throwable error,
            List<String> rawTrackingNumbersForSanitizing) {
        PageState safeState = state == null ? new PageState("", "", "") : state;
        StringBuilder reason = new StringBuilder();
        reason.append(nullToEmpty(summary));
        reason.append(" | currentUrl=").append(nullToEmpty(safeState.currentUrl()));
        reason.append(" | title=").append(nullToEmpty(safeState.title()));
        reason.append(" | bodyText=").append(truncate(normalize(safeState.bodyText()), BODY_TEXT_SUMMARY_MAX_LENGTH));
        if (error != null) {
            reason.append(" | exception=")
                    .append(error.getClass().getSimpleName())
                    .append(": ")
                    .append(truncate(firstLine(error.getMessage()), 500));
        }
        return truncate(MscTrackingSanitizer.sanitize(reason.toString(), rawTrackingNumbersForSanitizing), ERROR_REASON_MAX_LENGTH);
    }

    private static String firstLine(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\r", "\n").split("\n", 2)[0];
    }

    private static String normalize(String text) {
        return text == null ? "" : text.replaceAll("\\s+", " ").trim();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value == null ? "" : value;
        }
        return value.substring(0, maxLength);
    }

    record PageState(String currentUrl, String title, String bodyText) {
    }
}

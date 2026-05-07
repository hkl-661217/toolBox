package com.example.myaiproject.tool.msc;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MscTrackingTextParser {
    private static final Pattern DATE_PATTERN = Pattern.compile(
            "(20\\d{2}[-/.]\\d{1,2}[-/.]\\d{1,2}|\\d{1,2}[-/.]\\d{1,2}[-/.]20\\d{2})");
    private static final Set<String> STATUS_LABELS = Set.of(
            "current status", "当前状态");
    private static final Set<String> ETA_LABELS = Set.of(
            "eta", "estimated time of arrival", "预计到达时间", "卸货港预计到达时间");
    private static final Set<String> LATEST_NODE_LABELS = Set.of(
            "latest event", "latest movement", "latest node", "最新动向", "最新轨迹", "最近动态");

    public MscTrackingParsedFields parse(String visibleText) {
        if (visibleText == null || visibleText.isBlank()) {
            return MscTrackingParsedFields.empty();
        }

        List<String> lines = normalizeLines(visibleText);
        String currentStatus = findValueAfterLabel(lines, STATUS_LABELS);
        String eta = findEta(lines);
        String latestNode = findValueAfterLabel(lines, LATEST_NODE_LABELS);

        return new MscTrackingParsedFields(currentStatus, eta, latestNode);
    }

    private static List<String> normalizeLines(String text) {
        List<String> lines = new ArrayList<>();
        for (String rawLine : text.replace('\r', '\n').split("\\n")) {
            String line = rawLine.replaceAll("\\s+", " ").trim();
            if (!line.isBlank()) {
                lines.add(line);
            }
        }
        return lines;
    }

    private static String findEta(List<String> lines) {
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (containsAnyLabel(line, ETA_LABELS)) {
                Matcher sameLineMatcher = DATE_PATTERN.matcher(line);
                if (sameLineMatcher.find()) {
                    return sameLineMatcher.group(1);
                }
                String nextValue = nextNonLabelLine(lines, i + 1);
                Matcher nextLineMatcher = DATE_PATTERN.matcher(nextValue);
                if (nextLineMatcher.find()) {
                    return nextLineMatcher.group(1);
                }
                return nextValue;
            }
        }
        return "";
    }

    private static String findValueAfterLabel(List<String> lines, Set<String> labels) {
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (containsAnyLabel(line, labels)) {
                String valueFromSameLine = valueAfterSeparator(line);
                if (!valueFromSameLine.isBlank() && !isKnownLabel(valueFromSameLine)) {
                    return valueFromSameLine;
                }
                return nextNonLabelLine(lines, i + 1);
            }
        }
        return "";
    }

    private static String nextNonLabelLine(List<String> lines, int startIndex) {
        for (int i = startIndex; i < lines.size(); i++) {
            String candidate = lines.get(i);
            if (!isKnownLabel(candidate)) {
                return candidate;
            }
        }
        return "";
    }

    private static boolean containsAnyLabel(String line, Set<String> labels) {
        String normalized = normalizeForLabel(line);
        return labels.stream().anyMatch(normalized::contains);
    }

    private static boolean isKnownLabel(String line) {
        String normalized = normalizeForLabel(line);
        return STATUS_LABELS.contains(normalized)
                || ETA_LABELS.contains(normalized)
                || LATEST_NODE_LABELS.contains(normalized)
                || normalized.equals("date")
                || normalized.equals("日期")
                || normalized.equals("location")
                || normalized.equals("位置")
                || normalized.equals("description")
                || normalized.equals("描述");
    }

    private static String normalizeForLabel(String line) {
        return line.toLowerCase(Locale.ROOT)
                .replace("：", ":")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static String valueAfterSeparator(String line) {
        int colonIndex = Math.max(line.indexOf(':'), line.indexOf('：'));
        if (colonIndex < 0 || colonIndex == line.length() - 1) {
            return "";
        }
        return line.substring(colonIndex + 1).trim();
    }
}

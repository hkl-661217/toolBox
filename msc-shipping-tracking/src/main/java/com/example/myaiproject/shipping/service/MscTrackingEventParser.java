package com.example.myaiproject.shipping.service;

import com.example.myaiproject.shipping.model.ShippingTrackingEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class MscTrackingEventParser {
    private static final Logger log = LoggerFactory.getLogger(MscTrackingEventParser.class);
    private static final Pattern DATE_LINE = Pattern.compile("\\d{1,2}/\\d{1,2}/\\d{4}|20\\d{2}[-/.]\\d{1,2}[-/.]\\d{1,2}");
    // Pages shorter than this are typically empty/error stubs — header miss isn't worth alerting on.
    private static final int SUBSTANTIAL_PAGE_CHARS = 200;
    private static final int RAW_TEXT_EXCERPT_CHARS = 1024;

    private final ShippingTrackingMetrics metrics;

    public MscTrackingEventParser(ShippingTrackingMetrics metrics) {
        this.metrics = metrics;
    }

    public List<ShippingTrackingEvent> parse(String rawText) {
        if (rawText == null || rawText.isBlank()) {
            return List.of();
        }

        List<String> lines = normalizeLines(rawText);
        int tableStart = findEventTableStart(lines);
        if (tableStart < 0) {
            if (rawText.length() >= SUBSTANTIAL_PAGE_CHARS) {
                metrics.recordParserFailure("header_not_found");
                log.warn("MSC tracking parser could not find the event-table header "
                                + "(rawText {} chars). MSC may have changed the page structure. "
                                + "Excerpt (first {} chars): {}",
                        rawText.length(),
                        RAW_TEXT_EXCERPT_CHARS,
                        excerpt(rawText));
            }
            return List.of();
        }

        List<ShippingTrackingEvent> events = new ArrayList<>();
        int index = tableStart;
        while (index + 3 < lines.size()) {
            String date = lines.get(index);
            if (!DATE_LINE.matcher(date).matches()) {
                break;
            }
            String location = lines.get(index + 1);
            String description = lines.get(index + 2);
            String vesselVoyage = lines.get(index + 3);
            events.add(new ShippingTrackingEvent(date, location, description, vesselVoyage));
            index = index + 4;
            if (index < lines.size() && !DATE_LINE.matcher(lines.get(index)).matches()) {
                index++;
            }
        }
        return events;
    }

    private static int findEventTableStart(List<String> lines) {
        for (int i = 0; i + 3 < lines.size(); i++) {
            if (lines.get(i).equals("日期")
                    && lines.get(i + 1).equals("位置")
                    && lines.get(i + 2).equals("描述")
                    && lines.get(i + 3).equals("空载/满载/船舶/航次")) {
                int start = i + 4;
                if (start < lines.size() && lines.get(start).equals("设备处理设施名称")) {
                    return start + 1;
                }
                return start;
            }
        }
        return -1;
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

    private static String excerpt(String text) {
        if (text.length() <= RAW_TEXT_EXCERPT_CHARS) {
            return text;
        }
        return text.substring(0, RAW_TEXT_EXCERPT_CHARS) + "…(truncated)";
    }
}

package com.example.myaiproject.shipping.service;

import com.example.myaiproject.shipping.model.ShippingTrackingEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class MscTrackingEventParser {
    private static final Pattern DATE_LINE = Pattern.compile("\\d{1,2}/\\d{1,2}/\\d{4}|20\\d{2}[-/.]\\d{1,2}[-/.]\\d{1,2}");

    public List<ShippingTrackingEvent> parse(String rawText) {
        if (rawText == null || rawText.isBlank()) {
            return List.of();
        }

        List<String> lines = normalizeLines(rawText);
        int tableStart = findEventTableStart(lines);
        if (tableStart < 0) {
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
}

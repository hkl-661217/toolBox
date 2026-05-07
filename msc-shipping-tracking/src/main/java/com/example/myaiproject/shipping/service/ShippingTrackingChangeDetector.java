package com.example.myaiproject.shipping.service;

import com.example.myaiproject.shipping.model.ShippingTrackingEvent;
import com.example.myaiproject.shipping.model.ShippingTrackingEventChange;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import org.springframework.stereotype.Component;

@Component
public class ShippingTrackingChangeDetector {
    public List<ShippingTrackingEventChange> detect(
            List<ShippingTrackingEvent> previousEvents,
            List<ShippingTrackingEvent> currentEvents) {
        List<ShippingTrackingEvent> before = previousEvents == null ? List.of() : previousEvents;
        List<ShippingTrackingEvent> after = currentEvents == null ? List.of() : currentEvents;
        Map<String, Queue<ShippingTrackingEvent>> currentByKey = groupByEventKey(after);
        Map<String, Boolean> completedDeparturesByStage = completedDeparturesByStage(after);
        List<ShippingTrackingEventChange> changes = new ArrayList<>();

        for (ShippingTrackingEvent previous : before) {
            Queue<ShippingTrackingEvent> currentCandidates = currentByKey.get(eventKey(previous));
            ShippingTrackingEvent current = currentCandidates == null ? null : currentCandidates.poll();
            if (current == null) {
                if (isEstimatedDeparture(previous)
                        && completedDeparturesByStage.getOrDefault(departureStageKey(previous), false)) {
                    continue;
                }
                changes.add(new ShippingTrackingEventChange(
                        changes.size() + 1,
                        "删除物流节点",
                        previous,
                        null));
                continue;
            }
            if (Objects.equals(previous, current)) {
                continue;
            }
            if (isEstimatedDeparture(previous)
                    && completedDeparturesByStage.getOrDefault(departureStageKey(previous), false)) {
                continue;
            }
            String changeType = changeType(previous, current);
            changes.add(new ShippingTrackingEventChange(
                    changes.size() + 1,
                    changeType,
                    previous,
                    current));
        }

        Map<String, Integer> remainingCurrentCounts = remainingCounts(currentByKey);
        for (ShippingTrackingEvent current : after) {
            String key = eventKey(current);
            int remaining = remainingCurrentCounts.getOrDefault(key, 0);
            if (remaining <= 0) {
                continue;
            }
            if (isCompletedDeparture(current)) {
                remainingCurrentCounts.put(key, remaining - 1);
                continue;
            }
            changes.add(new ShippingTrackingEventChange(
                    changes.size() + 1,
                    "新增物流节点",
                    null,
                    current));
            remainingCurrentCounts.put(key, remaining - 1);
        }
        return changes;
    }

    private static String changeType(ShippingTrackingEvent previous, ShippingTrackingEvent current) {
        boolean dateChanged = !Objects.equals(normalize(previous.date()), normalize(current.date()));
        if (dateChanged && isEstimatedDeparture(previous) && isEstimatedDeparture(current)) {
            return "ETD_CHANGED";
        }
        return dateChanged ? "日期变化" : "物流节点变化";
    }

    private static Map<String, Queue<ShippingTrackingEvent>> groupByEventKey(List<ShippingTrackingEvent> events) {
        Map<String, Queue<ShippingTrackingEvent>> grouped = new HashMap<>();
        for (ShippingTrackingEvent event : events) {
            grouped.computeIfAbsent(eventKey(event), key -> new ArrayDeque<>()).add(event);
        }
        return grouped;
    }

    private static Map<String, Boolean> completedDeparturesByStage(List<ShippingTrackingEvent> events) {
        Map<String, Boolean> stages = new HashMap<>();
        for (ShippingTrackingEvent event : events) {
            if (isCompletedDeparture(event)) {
                stages.put(departureStageKey(event), true);
            }
        }
        return stages;
    }

    private static Map<String, Integer> remainingCounts(Map<String, Queue<ShippingTrackingEvent>> groupedEvents) {
        Map<String, Integer> counts = new HashMap<>();
        for (Map.Entry<String, Queue<ShippingTrackingEvent>> entry : groupedEvents.entrySet()) {
            if (!entry.getValue().isEmpty()) {
                counts.put(entry.getKey(), entry.getValue().size());
            }
        }
        return counts;
    }

    private static String eventKey(ShippingTrackingEvent event) {
        return normalize(event.location())
                + "\n"
                + normalize(event.description())
                + "\n"
                + normalize(event.vesselVoyage());
    }

    private static String departureStageKey(ShippingTrackingEvent event) {
        return normalize(event.location())
                + "\n"
                + normalize(event.vesselVoyage());
    }

    private static boolean isEstimatedDeparture(ShippingTrackingEvent event) {
        return normalize(event.description()).toLowerCase().contains("estimated time of departure");
    }

    private static boolean isCompletedDeparture(ShippingTrackingEvent event) {
        String description = normalize(event.description()).toLowerCase();
        return description.contains("actual time of departure")
                || description.contains("vessel departed")
                || "departed".equals(description)
                || description.contains(" departed");
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}

package com.example.myaiproject.shipping.repo;

import com.example.myaiproject.shipping.model.ShippingTrackingEvent;
import com.example.myaiproject.shipping.model.ShippingTrackingSnapshot;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class ShippingTrackingSnapshotRepository {
    private static final TypeReference<List<ShippingTrackingEvent>> EVENT_LIST = new TypeReference<>() {
    };

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public ShippingTrackingSnapshotRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public ShippingTrackingSnapshot insert(
            long bindingId,
            OffsetDateTime queryTime,
            String status,
            List<ShippingTrackingEvent> events,
            String rawText,
            String eta,
            String latestNode,
            String screenshotPath,
            String errorReason,
            boolean baseline,
            OffsetDateTime now) {
        String eventsJson = toJson(events == null ? List.of() : events);
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    insert into shipping_tracking_snapshot
                        (binding_id, query_time, status, events_json, raw_text, eta, latest_node,
                         screenshot_path, error_reason, baseline, created_at)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, bindingId);
            statement.setObject(2, queryTime);
            statement.setString(3, status);
            statement.setString(4, eventsJson);
            statement.setString(5, rawText);
            statement.setString(6, emptyToNull(eta));
            statement.setString(7, emptyToNull(latestNode));
            statement.setString(8, emptyToNull(screenshotPath));
            statement.setString(9, emptyToNull(errorReason));
            statement.setBoolean(10, baseline);
            statement.setObject(11, now);
            return statement;
        }, keyHolder);
        return findById(keyHolder.getKey().longValue()).orElseThrow();
    }

    public Optional<ShippingTrackingSnapshot> findById(long id) {
        List<ShippingTrackingSnapshot> rows = jdbcTemplate.query(
                "select * from shipping_tracking_snapshot where id = ?",
                mapper(),
                id);
        return rows.stream().findFirst();
    }

    public Optional<ShippingTrackingSnapshot> findLatestSuccess(long bindingId) {
        List<ShippingTrackingSnapshot> rows = jdbcTemplate.query("""
                select * from shipping_tracking_snapshot
                where binding_id = ? and status = 'SUCCESS'
                order by query_time desc, id desc
                limit 1
                """, mapper(), bindingId);
        return rows.stream().findFirst();
    }

    private RowMapper<ShippingTrackingSnapshot> mapper() {
        return (rs, rowNum) -> new ShippingTrackingSnapshot(
                rs.getLong("id"),
                rs.getLong("binding_id"),
                rs.getObject("query_time", OffsetDateTime.class),
                rs.getString("status"),
                fromJson(rs.getString("events_json")),
                rs.getString("raw_text"),
                rs.getString("eta"),
                rs.getString("latest_node"),
                rs.getString("screenshot_path"),
                rs.getString("error_reason"),
                rs.getBoolean("baseline"),
                rs.getObject("created_at", OffsetDateTime.class));
    }

    private String toJson(List<ShippingTrackingEvent> events) {
        try {
            return objectMapper.writeValueAsString(events);
        } catch (Exception error) {
            throw new IllegalStateException("Failed to serialize tracking events.", error);
        }
    }

    private List<ShippingTrackingEvent> fromJson(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, EVENT_LIST);
        } catch (Exception error) {
            throw new IllegalStateException("Failed to deserialize tracking events.", error);
        }
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}

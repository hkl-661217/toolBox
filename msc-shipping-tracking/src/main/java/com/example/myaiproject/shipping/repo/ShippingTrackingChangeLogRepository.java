package com.example.myaiproject.shipping.repo;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.OffsetDateTime;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class ShippingTrackingChangeLogRepository {
    private final JdbcTemplate jdbcTemplate;

    public ShippingTrackingChangeLogRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public long insert(
            long bindingId,
            long previousSnapshotId,
            long currentSnapshotId,
            String changeType,
            String changeSummary,
            String beforeJson,
            String afterJson,
            boolean emailSent,
            OffsetDateTime emailSentTime,
            OffsetDateTime now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    insert into shipping_tracking_change_log
                        (binding_id, previous_snapshot_id, current_snapshot_id, change_type,
                         change_summary, before_json, after_json, email_sent, email_sent_time, created_at)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, bindingId);
            statement.setLong(2, previousSnapshotId);
            statement.setLong(3, currentSnapshotId);
            statement.setString(4, changeType);
            statement.setString(5, changeSummary);
            statement.setString(6, beforeJson);
            statement.setString(7, afterJson);
            statement.setBoolean(8, emailSent);
            statement.setObject(9, emailSentTime);
            statement.setObject(10, now);
            return statement;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }
}

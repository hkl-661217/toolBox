package com.example.myaiproject.shipping.repo;

import com.example.myaiproject.shipping.model.ShippingTrackingChangeLog;
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

    public Optional<ShippingTrackingChangeLog> findById(long id) {
        List<ShippingTrackingChangeLog> rows = jdbcTemplate.query(
                "select * from shipping_tracking_change_log where id = ?",
                mapper(),
                id);
        return rows.stream().findFirst();
    }

    /**
     * Returns rows that still need an email sent, are within the retry window,
     * and have not yet hit the attempt cap. Ordered oldest-first.
     */
    public List<ShippingTrackingChangeLog> findPendingRetries(int maxAttempts, OffsetDateTime ageCutoff) {
        return jdbcTemplate.query("""
                select * from shipping_tracking_change_log
                where email_sent = false
                  and give_up_at is null
                  and retry_count < ?
                  and created_at >= ?
                order by created_at asc
                """,
                mapper(),
                maxAttempts,
                ageCutoff);
    }

    /**
     * Returns rows whose email never went through and which have aged past the
     * retry window, but have not yet been marked as given up. Caller is
     * expected to log and call {@link #markGivenUp}.
     */
    public List<ShippingTrackingChangeLog> findAgedOut(OffsetDateTime ageCutoff) {
        return jdbcTemplate.query("""
                select * from shipping_tracking_change_log
                where email_sent = false
                  and give_up_at is null
                  and created_at < ?
                order by created_at asc
                """,
                mapper(),
                ageCutoff);
    }

    public void markGivenUp(long id, OffsetDateTime at) {
        jdbcTemplate.update(
                "update shipping_tracking_change_log set give_up_at = ? where id = ?",
                at,
                id);
    }

    public void markEmailSent(long id, OffsetDateTime sentAt) {
        jdbcTemplate.update("""
                update shipping_tracking_change_log
                set email_sent = true,
                    email_sent_time = ?,
                    retry_count = retry_count + 1,
                    last_retry_at = ?
                where id = ?
                """,
                sentAt,
                sentAt,
                id);
    }

    public void bumpRetryCount(long id, OffsetDateTime attemptAt) {
        jdbcTemplate.update("""
                update shipping_tracking_change_log
                set retry_count = retry_count + 1,
                    last_retry_at = ?
                where id = ?
                """,
                attemptAt,
                id);
    }

    private static RowMapper<ShippingTrackingChangeLog> mapper() {
        return (rs, rowNum) -> new ShippingTrackingChangeLog(
                rs.getLong("id"),
                rs.getLong("binding_id"),
                rs.getLong("previous_snapshot_id"),
                rs.getLong("current_snapshot_id"),
                rs.getString("change_type"),
                rs.getString("change_summary"),
                rs.getString("before_json"),
                rs.getString("after_json"),
                rs.getBoolean("email_sent"),
                rs.getObject("email_sent_time", OffsetDateTime.class),
                rs.getInt("retry_count"),
                rs.getObject("last_retry_at", OffsetDateTime.class),
                rs.getObject("give_up_at", OffsetDateTime.class),
                rs.getObject("created_at", OffsetDateTime.class));
    }
}

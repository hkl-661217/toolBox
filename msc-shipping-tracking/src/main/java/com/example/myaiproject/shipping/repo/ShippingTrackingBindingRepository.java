package com.example.myaiproject.shipping.repo;

import com.example.myaiproject.shipping.model.ShippingTrackingBinding;
import com.example.myaiproject.shipping.support.ShippingTrackingFieldSanitizer;
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
public class ShippingTrackingBindingRepository {
    private final JdbcTemplate jdbcTemplate;

    public ShippingTrackingBindingRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public ShippingTrackingBinding insert(String orderNo, String bookingNo, OffsetDateTime now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    insert into shipping_tracking_binding
                        (order_no, booking_no, carrier, enabled, created_at, updated_at)
                    values (?, ?, 'MSC', true, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, orderNo);
            statement.setString(2, bookingNo);
            statement.setObject(3, now);
            statement.setObject(4, now);
            return statement;
        }, keyHolder);
        return findById(keyHolder.getKey().longValue()).orElseThrow();
    }

    public Optional<ShippingTrackingBinding> findById(long id) {
        List<ShippingTrackingBinding> rows = jdbcTemplate.query(
                "select * from shipping_tracking_binding where id = ?",
                mapper(),
                id);
        return rows.stream().findFirst();
    }

    public Optional<ShippingTrackingBinding> findByOrderNo(String orderNo) {
        return jdbcTemplate.query(
                "select * from shipping_tracking_binding where order_no = ?",
                mapper(),
                orderNo).stream().findFirst();
    }

    public Optional<ShippingTrackingBinding> findByBookingNo(String bookingNo) {
        return jdbcTemplate.query(
                "select * from shipping_tracking_binding where booking_no = ?",
                mapper(),
                bookingNo).stream().findFirst();
    }

    public List<ShippingTrackingBinding> findAll() {
        return jdbcTemplate.query(
                "select * from shipping_tracking_binding order by id desc",
                mapper());
    }

    public List<ShippingTrackingBinding> findEnabled(int limit) {
        return jdbcTemplate.query(
                "select * from shipping_tracking_binding where enabled = true order by id asc limit ?",
                mapper(),
                Math.max(0, limit));
    }

    public void updateAfterQuery(
            long id,
            String status,
            String eta,
            String latestNode,
            String departure,
            OffsetDateTime queryTime,
            OffsetDateTime now) {
        String safeStatus = ShippingTrackingFieldSanitizer.truncate(status, ShippingTrackingFieldSanitizer.STATUS_MAX_LENGTH);
        String safeEta = ShippingTrackingFieldSanitizer.truncate(eta, ShippingTrackingFieldSanitizer.ETA_MAX_LENGTH);
        String safeLatestNode = ShippingTrackingFieldSanitizer.truncate(latestNode, ShippingTrackingFieldSanitizer.LATEST_NODE_MAX_LENGTH);
        String safeDeparture = ShippingTrackingFieldSanitizer.truncate(departure, ShippingTrackingFieldSanitizer.LATEST_NODE_MAX_LENGTH);
        jdbcTemplate.update("""
                update shipping_tracking_binding
                set last_status = ?, last_eta = ?, last_node = ?, last_departure = ?, last_query_time = ?, updated_at = ?
                where id = ?
                """, safeStatus, emptyToNull(safeEta), emptyToNull(safeLatestNode), emptyToNull(safeDeparture), queryTime, now, id);
    }

    public void disable(long id, OffsetDateTime now) {
        jdbcTemplate.update(
                "update shipping_tracking_binding set enabled = false, updated_at = ? where id = ?",
                now,
                id);
    }

    public void enable(long id, OffsetDateTime now) {
        jdbcTemplate.update(
                "update shipping_tracking_binding set enabled = true, updated_at = ? where id = ?",
                now,
                id);
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static RowMapper<ShippingTrackingBinding> mapper() {
        return (rs, rowNum) -> new ShippingTrackingBinding(
                rs.getLong("id"),
                rs.getString("order_no"),
                rs.getString("booking_no"),
                rs.getString("carrier"),
                rs.getBoolean("enabled"),
                rs.getString("last_status"),
                rs.getString("last_eta"),
                rs.getString("last_node"),
                rs.getString("last_departure"),
                rs.getObject("last_query_time", OffsetDateTime.class),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class));
    }
}

package com.example.myaiproject.shipping.repo;

import com.example.myaiproject.shipping.model.NotificationAccount;
import com.example.myaiproject.shipping.support.ShippingTrackingCryptoService;
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
public class NotificationAccountRepository {
    private final JdbcTemplate jdbcTemplate;
    private final ShippingTrackingCryptoService crypto;

    public NotificationAccountRepository(JdbcTemplate jdbcTemplate, ShippingTrackingCryptoService crypto) {
        this.jdbcTemplate = jdbcTemplate;
        this.crypto = crypto;
    }

    public NotificationAccount insert(String email, String smtpPassword, OffsetDateTime now) {
        String encrypted = crypto.encrypt(smtpPassword);
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    insert into shipping_tracking_notification_account
                        (email, smtp_password, enabled, created_at, updated_at)
                    values (?, ?, true, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, email);
            ps.setString(2, encrypted);
            ps.setObject(3, now);
            ps.setObject(4, now);
            return ps;
        }, keyHolder);
        return findById(keyHolder.getKey().longValue()).orElseThrow();
    }

    public Optional<NotificationAccount> findById(long id) {
        List<NotificationAccount> rows = jdbcTemplate.query(
                "select * from shipping_tracking_notification_account where id = ?",
                mapper(),
                id);
        return rows.stream().findFirst();
    }

    public List<NotificationAccount> findAll() {
        return jdbcTemplate.query(
                "select * from shipping_tracking_notification_account order by id asc",
                mapper());
    }

    public List<NotificationAccount> findEnabled() {
        return jdbcTemplate.query(
                "select * from shipping_tracking_notification_account where enabled = true order by id asc",
                mapper());
    }

    public void delete(long id) {
        jdbcTemplate.update("delete from shipping_tracking_notification_account where id = ?", id);
    }

    public void setEnabled(long id, boolean enabled, OffsetDateTime now) {
        jdbcTemplate.update(
                "update shipping_tracking_notification_account set enabled = ?, updated_at = ? where id = ?",
                enabled,
                now,
                id);
    }

    private RowMapper<NotificationAccount> mapper() {
        return (rs, rowNum) -> new NotificationAccount(
                rs.getLong("id"),
                rs.getString("email"),
                crypto.decryptIfNeeded(rs.getString("smtp_password")),
                rs.getBoolean("enabled"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class));
    }
}

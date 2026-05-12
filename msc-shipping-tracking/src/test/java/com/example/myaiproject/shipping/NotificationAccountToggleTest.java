package com.example.myaiproject.shipping;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.myaiproject.shipping.model.NotificationAccount;
import com.example.myaiproject.shipping.service.NotificationAccountService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class NotificationAccountToggleTest {

    @Autowired
    NotificationAccountService service;
    @Autowired
    MockMvc mockMvc;
    @Autowired
    JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanAccounts() {
        jdbcTemplate.update("delete from shipping_tracking_notification_account");
    }

    @Test
    void setEnabledTogglesAccountFlag() {
        NotificationAccount account = service.create("a@example.com", "auth-code-aaaa");
        assertTrue(account.enabled());

        service.setEnabled(account.id(), false);
        assertFalse(reloadEnabled(account.id()));

        service.setEnabled(account.id(), true);
        assertTrue(reloadEnabled(account.id()));
    }

    @Test
    void setEnabledOnMissingAccountRejectsWithFriendlyMessage() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> service.setEnabled(99_999L, false));
        assertEquals("通知邮箱不存在: 99999", error.getMessage());
    }

    @Test
    void listEnabledExcludesDisabledAccounts() {
        NotificationAccount keep = service.create("keep@example.com", "code-keep");
        NotificationAccount drop = service.create("drop@example.com", "code-drop");
        service.setEnabled(drop.id(), false);

        var enabled = service.listEnabled();

        assertEquals(1, enabled.size());
        assertEquals(keep.id(), enabled.get(0).id());
    }

    @Test
    void enableAndDisableApiTogglesEnabledFlag() throws Exception {
        NotificationAccount account = service.create("api@example.com", "code-api-toggle");

        mockMvc.perform(post("/api/shipping-tracking/notification-accounts/{id}/disable", account.id()))
                .andExpect(status().isNoContent());
        assertFalse(reloadEnabled(account.id()));

        mockMvc.perform(post("/api/shipping-tracking/notification-accounts/{id}/enable", account.id()))
                .andExpect(status().isNoContent());
        assertTrue(reloadEnabled(account.id()));
    }

    private boolean reloadEnabled(long id) {
        return jdbcTemplate.queryForObject(
                "select enabled from shipping_tracking_notification_account where id = ?",
                Boolean.class,
                id);
    }
}

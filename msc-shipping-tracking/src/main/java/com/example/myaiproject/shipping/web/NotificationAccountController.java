package com.example.myaiproject.shipping.web;

import com.example.myaiproject.shipping.model.NotificationAccount;
import com.example.myaiproject.shipping.service.NotificationAccountService;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/shipping-tracking/notification-accounts")
public class NotificationAccountController {
    private final NotificationAccountService service;

    public NotificationAccountController(NotificationAccountService service) {
        this.service = service;
    }

    @GetMapping
    public List<AccountResponse> list() {
        return service.listAll().stream().map(AccountResponse::from).toList();
    }

    @PostMapping
    public AccountResponse create(@RequestBody CreateAccountRequest request) {
        NotificationAccount account = service.create(request.email(), request.smtpPassword());
        return AccountResponse.from(account);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/enable")
    public ResponseEntity<Void> enable(@PathVariable long id) {
        service.setEnabled(id, true);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/disable")
    public ResponseEntity<Void> disable(@PathVariable long id) {
        service.setEnabled(id, false);
        return ResponseEntity.noContent().build();
    }

    public record CreateAccountRequest(String email, String smtpPassword) {
    }

    /** Response DTO — never echoes the raw SMTP password back to the client. */
    public record AccountResponse(
            long id,
            String email,
            boolean enabled,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt) {
        static AccountResponse from(NotificationAccount account) {
            return new AccountResponse(
                    account.id(),
                    account.email(),
                    account.enabled(),
                    account.createdAt(),
                    account.updatedAt());
        }
    }
}

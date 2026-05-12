package com.example.myaiproject.shipping.service;

import com.example.myaiproject.shipping.model.NotificationAccount;
import com.example.myaiproject.shipping.repo.NotificationAccountRepository;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class NotificationAccountService {
    private final NotificationAccountRepository repository;

    public NotificationAccountService(NotificationAccountRepository repository) {
        this.repository = repository;
    }

    public NotificationAccount create(String email, String smtpPassword) {
        String trimmedEmail = email == null ? "" : email.trim();
        String trimmedPassword = smtpPassword == null ? "" : smtpPassword.trim();
        if (trimmedEmail.isEmpty()) {
            throw new IllegalArgumentException("email is required");
        }
        if (!trimmedEmail.contains("@")) {
            throw new IllegalArgumentException("email looks invalid: " + trimmedEmail);
        }
        if (trimmedPassword.isEmpty()) {
            throw new IllegalArgumentException("smtpPassword is required");
        }
        return repository.insert(trimmedEmail, trimmedPassword, OffsetDateTime.now());
    }

    public List<NotificationAccount> listAll() {
        return repository.findAll();
    }

    public List<NotificationAccount> listEnabled() {
        return repository.findEnabled();
    }

    public void delete(long id) {
        repository.delete(id);
    }
}

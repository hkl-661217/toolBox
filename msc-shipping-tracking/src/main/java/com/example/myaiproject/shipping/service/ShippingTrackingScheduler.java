package com.example.myaiproject.shipping.service;

import com.example.myaiproject.shipping.model.ShippingTrackingBinding;
import com.example.myaiproject.shipping.repo.ShippingTrackingBindingRepository;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class ShippingTrackingScheduler {
    private static final Logger log = LoggerFactory.getLogger(ShippingTrackingScheduler.class);

    private final ShippingTrackingBindingRepository bindingRepository;
    private final ShippingTrackingService trackingService;
    private final ShippingTrackingProperties properties;

    public ShippingTrackingScheduler(
            ShippingTrackingBindingRepository bindingRepository,
            ShippingTrackingService trackingService,
            ShippingTrackingProperties properties) {
        this.bindingRepository = bindingRepository;
        this.trackingService = trackingService;
        this.properties = properties;
    }

    @Scheduled(cron = "${shipping.tracking.cron:0 0 9 * * *}")
    public void runDailyBatch() {
        List<ShippingTrackingBinding> bindings = bindingRepository.findEnabled(properties.getBatchLimit());
        for (int i = 0; i < bindings.size(); i++) {
            ShippingTrackingBinding binding = bindings.get(i);
            try {
                trackingService.syncBindingForBatch(binding);
            } catch (Exception error) {
                log.warn("Shipping tracking batch failed for binding {}.", binding.id(), error);
            }
            if (i < bindings.size() - 1) {
                sleepBetweenBindings();
            }
        }
    }

    private void sleepBetweenBindings() {
        long minMillis = Duration.ofSeconds(Math.max(0, properties.getDelayMinSeconds())).toMillis();
        long maxMillis = Duration.ofSeconds(Math.max(0, properties.getDelayMaxSeconds())).toMillis();
        if (maxMillis < minMillis) {
            maxMillis = minMillis;
        }
        long sleepMillis = minMillis == maxMillis
                ? minMillis
                : ThreadLocalRandom.current().nextLong(minMillis, maxMillis + 1);
        if (sleepMillis <= 0) {
            return;
        }
        try {
            Thread.sleep(sleepMillis);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
    }
}

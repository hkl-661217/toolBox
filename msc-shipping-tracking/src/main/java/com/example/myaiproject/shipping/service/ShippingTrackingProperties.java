package com.example.myaiproject.shipping.service;

import java.util.Arrays;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "shipping.tracking")
public class ShippingTrackingProperties {
    private String notifyEmails = "";
    private String cron = "0 0 9 * * *";
    private int batchLimit = 5;
    private int delayMinSeconds = 10;
    private int delayMaxSeconds = 20;
    private String screenshotsDir = "data/shipping-tracking/screenshots";
    private long chromiumLaunchTimeoutMs = 180_000L;

    public String getNotifyEmails() {
        return notifyEmails;
    }

    public void setNotifyEmails(String notifyEmails) {
        this.notifyEmails = notifyEmails;
    }

    public String getCron() {
        return cron;
    }

    public void setCron(String cron) {
        this.cron = cron;
    }

    public int getBatchLimit() {
        return batchLimit;
    }

    public void setBatchLimit(int batchLimit) {
        this.batchLimit = batchLimit;
    }

    public int getDelayMinSeconds() {
        return delayMinSeconds;
    }

    public void setDelayMinSeconds(int delayMinSeconds) {
        this.delayMinSeconds = delayMinSeconds;
    }

    public int getDelayMaxSeconds() {
        return delayMaxSeconds;
    }

    public void setDelayMaxSeconds(int delayMaxSeconds) {
        this.delayMaxSeconds = delayMaxSeconds;
    }

    public String getScreenshotsDir() {
        return screenshotsDir;
    }

    public void setScreenshotsDir(String screenshotsDir) {
        this.screenshotsDir = screenshotsDir;
    }

    public long getChromiumLaunchTimeoutMs() {
        return chromiumLaunchTimeoutMs;
    }

    public void setChromiumLaunchTimeoutMs(long chromiumLaunchTimeoutMs) {
        this.chromiumLaunchTimeoutMs = chromiumLaunchTimeoutMs;
    }

    public List<String> notifyEmailList() {
        if (notifyEmails == null || notifyEmails.isBlank()) {
            return List.of();
        }
        return Arrays.stream(notifyEmails.split(","))
                .map(String::trim)
                .filter(email -> !email.isBlank())
                .toList();
    }
}

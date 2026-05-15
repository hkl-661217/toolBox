package com.example.myaiproject.shipping.service;

import java.util.Arrays;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "shipping.tracking")
public class ShippingTrackingProperties {
    private String notifyEmails = "";
    private String cron = "0 0 9 * * *";
    private int minRequeryHours = 20;
    private int delayMinSeconds = 10;
    private int delayMaxSeconds = 20;
    private String screenshotsDir = "data/shipping-tracking/screenshots";
    private long chromiumLaunchTimeoutMs = 180_000L;
    private boolean chromiumHeadless = true;
    private boolean chromiumStealthEnabled = true;
    private String client = "curl-impersonate";
    private String pythonExecutable = "python3";
    private String pythonScriptPath = "scripts/msc_tracking_query.py";
    private long curlImpersonateTimeoutMs = 90_000L;
    private String encryptionKey = "";
    private String retryCron = "0 */15 * * * *";
    private int retryMaxAttempts = 6;
    private int retryMaxAgeHours = 24;

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

    public int getMinRequeryHours() {
        return minRequeryHours;
    }

    public void setMinRequeryHours(int minRequeryHours) {
        this.minRequeryHours = minRequeryHours;
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

    public boolean isChromiumHeadless() {
        return chromiumHeadless;
    }

    public void setChromiumHeadless(boolean chromiumHeadless) {
        this.chromiumHeadless = chromiumHeadless;
    }

    public boolean isChromiumStealthEnabled() {
        return chromiumStealthEnabled;
    }

    public void setChromiumStealthEnabled(boolean chromiumStealthEnabled) {
        this.chromiumStealthEnabled = chromiumStealthEnabled;
    }

    public String getClient() {
        return client;
    }

    public void setClient(String client) {
        this.client = client;
    }

    public String getPythonExecutable() {
        return pythonExecutable;
    }

    public void setPythonExecutable(String pythonExecutable) {
        this.pythonExecutable = pythonExecutable;
    }

    public String getPythonScriptPath() {
        return pythonScriptPath;
    }

    public void setPythonScriptPath(String pythonScriptPath) {
        this.pythonScriptPath = pythonScriptPath;
    }

    public long getCurlImpersonateTimeoutMs() {
        return curlImpersonateTimeoutMs;
    }

    public void setCurlImpersonateTimeoutMs(long curlImpersonateTimeoutMs) {
        this.curlImpersonateTimeoutMs = curlImpersonateTimeoutMs;
    }

    public String getEncryptionKey() {
        return encryptionKey;
    }

    public void setEncryptionKey(String encryptionKey) {
        this.encryptionKey = encryptionKey;
    }

    public String getRetryCron() {
        return retryCron;
    }

    public void setRetryCron(String retryCron) {
        this.retryCron = retryCron;
    }

    public int getRetryMaxAttempts() {
        return retryMaxAttempts;
    }

    public void setRetryMaxAttempts(int retryMaxAttempts) {
        this.retryMaxAttempts = retryMaxAttempts;
    }

    public int getRetryMaxAgeHours() {
        return retryMaxAgeHours;
    }

    public void setRetryMaxAgeHours(int retryMaxAgeHours) {
        this.retryMaxAgeHours = retryMaxAgeHours;
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

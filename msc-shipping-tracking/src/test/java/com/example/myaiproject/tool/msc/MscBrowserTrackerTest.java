package com.example.myaiproject.tool.msc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.microsoft.playwright.BrowserType;
import org.junit.jupiter.api.Test;

class MscBrowserTrackerTest {
    @Test
    void launchOptionsCarryConfiguredTimeoutForBothChromeAndChromiumFallback() {
        long timeoutMs = 180_000L;

        BrowserType.LaunchOptions chromeOptions = MscBrowserTracker.buildChromeLaunchOptions(timeoutMs);
        BrowserType.LaunchOptions chromiumOptions = MscBrowserTracker.buildChromiumLaunchOptions(timeoutMs);

        assertEquals(180_000.0, chromeOptions.timeout);
        assertEquals("chrome", chromeOptions.channel);
        assertEquals(Boolean.FALSE, chromeOptions.headless);
        assertEquals(180_000.0, chromiumOptions.timeout);
        assertEquals(Boolean.FALSE, chromiumOptions.headless);
        assertEquals(180_000L, MscBrowserTracker.DEFAULT_CHROMIUM_LAUNCH_TIMEOUT_MS);
        assertThrows(IllegalArgumentException.class, () -> new MscBrowserTracker(0));
    }
}

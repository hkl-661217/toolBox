package com.example.myaiproject.tool.msc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.microsoft.playwright.BrowserType;
import org.junit.jupiter.api.Test;

class MscBrowserTrackerTest {
    @Test
    void launchOptionsCarryConfiguredTimeoutAndHeadlessForBothChromeAndChromiumFallback() {
        long timeoutMs = 180_000L;

        BrowserType.LaunchOptions chromeHeadless = MscBrowserTracker.buildChromeLaunchOptions(timeoutMs, true);
        BrowserType.LaunchOptions chromiumHeadless = MscBrowserTracker.buildChromiumLaunchOptions(timeoutMs, true);
        BrowserType.LaunchOptions chromeHeaded = MscBrowserTracker.buildChromeLaunchOptions(timeoutMs, false);
        BrowserType.LaunchOptions chromiumHeaded = MscBrowserTracker.buildChromiumLaunchOptions(timeoutMs, false);

        assertEquals(180_000.0, chromeHeadless.timeout);
        assertEquals("chrome", chromeHeadless.channel);
        assertEquals(Boolean.TRUE, chromeHeadless.headless);
        assertEquals(180_000.0, chromiumHeadless.timeout);
        assertEquals(Boolean.TRUE, chromiumHeadless.headless);

        assertEquals(Boolean.FALSE, chromeHeaded.headless);
        assertEquals(Boolean.FALSE, chromiumHeaded.headless);

        assertEquals(180_000L, MscBrowserTracker.DEFAULT_CHROMIUM_LAUNCH_TIMEOUT_MS);
        assertThrows(IllegalArgumentException.class, () -> new MscBrowserTracker(0));
        assertThrows(IllegalArgumentException.class, () -> new MscBrowserTracker(0, true));
    }
}

package com.example.myaiproject.tool.msc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.microsoft.playwright.BrowserType;
import org.junit.jupiter.api.Test;

class MscBrowserTrackerTest {
    private static final long TIMEOUT = 180_000L;

    @Test
    void launchOptionsCarryConfiguredTimeoutAndHeadlessForBothChromeAndChromiumFallback() {
        BrowserType.LaunchOptions chromeHeadless = MscBrowserTracker.buildChromeLaunchOptions(TIMEOUT, true, true);
        BrowserType.LaunchOptions chromiumHeadless = MscBrowserTracker.buildChromiumLaunchOptions(TIMEOUT, true, true);
        BrowserType.LaunchOptions chromeHeaded = MscBrowserTracker.buildChromeLaunchOptions(TIMEOUT, false, true);
        BrowserType.LaunchOptions chromiumHeaded = MscBrowserTracker.buildChromiumLaunchOptions(TIMEOUT, false, true);

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
        assertThrows(IllegalArgumentException.class, () -> new MscBrowserTracker(0, true, true));
    }

    @Test
    void stealthEnabledAddsLaunchArgsAndIgnoresAutomationFlag() {
        BrowserType.LaunchOptions stealthy = MscBrowserTracker.buildChromeLaunchOptions(TIMEOUT, true, true);

        assertNotNull(stealthy.args);
        assertTrue(stealthy.args.contains("--disable-blink-features=AutomationControlled"));
        assertTrue(stealthy.args.contains("--no-sandbox"));
        assertTrue(stealthy.args.contains("--disable-dev-shm-usage"));
        assertNotNull(stealthy.ignoreDefaultArgs);
        assertTrue(stealthy.ignoreDefaultArgs.contains("--enable-automation"));
    }

    @Test
    void stealthDisabledLeavesLaunchArgsUntouched() {
        BrowserType.LaunchOptions plain = MscBrowserTracker.buildChromiumLaunchOptions(TIMEOUT, true, false);

        assertNull(plain.args);
        assertNull(plain.ignoreDefaultArgs);
    }

    @Test
    void stealthConstantsAreDefined() {
        assertFalse(MscBrowserTracker.STEALTH_USER_AGENT.isBlank());
        assertTrue(MscBrowserTracker.STEALTH_USER_AGENT.contains("Chrome/"));
        assertFalse(MscBrowserTracker.STEALTH_INIT_SCRIPT.isBlank());
        assertTrue(MscBrowserTracker.STEALTH_INIT_SCRIPT.contains("navigator"));
        assertTrue(MscBrowserTracker.STEALTH_INIT_SCRIPT.contains("webdriver"));
    }
}

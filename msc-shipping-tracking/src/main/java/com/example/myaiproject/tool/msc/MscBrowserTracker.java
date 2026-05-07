package com.example.myaiproject.tool.msc;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.TimeoutError;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.WaitUntilState;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MscBrowserTracker implements AutoCloseable {
    public static final String TRACKING_URL = "https://www.msccargo.cn/zh/track-a-shipment";
    private static final Logger LOGGER = LoggerFactory.getLogger(MscBrowserTracker.class);
    private static final double PAGE_NAVIGATION_TIMEOUT_MS = 120_000;
    private static final long PAGE_READY_TIMEOUT_MS = 75_000;

    private final Playwright playwright;
    private final Browser browser;
    private final BrowserContext context;
    private final MscTrackingTextParser textParser = new MscTrackingTextParser();

    public MscBrowserTracker() {
        this.playwright = Playwright.create();
        this.browser = launchVisibleBrowser(playwright);
        this.context = browser.newContext(new Browser.NewContextOptions()
                .setLocale("zh-CN")
                .setViewportSize(1440, 1000));
    }

    public MscTrackingResult query(
            String trackingNo,
            MscTrackingQueryType queryType,
            int sequence,
            Path screenshotsDir,
            List<String> rawTrackingNumbersForSanitizing) {
        String trackingNoMasked = MscTrackingSanitizer.mask(trackingNo);
        String queriedAt = now();
        Path screenshotFile = screenshotFile(screenshotsDir, sequence, trackingNoMasked);
        Page page = context.newPage();

        try {
            navigateToTrackingPage(page);
            dismissCookieBanners(page);

            String initialText = readVisibleText(page);
            if (MscPageDiagnostics.isAccessDenied(initialText)) {
                String screenshotPath = safeScreenshot(page, screenshotFile, rawTrackingNumbersForSanitizing);
                String rawText = MscTrackingSanitizer.sanitize(initialText, rawTrackingNumbersForSanitizing);
                return MscTrackingResult.of(
                        trackingNoMasked,
                        queryType,
                        MscTrackingStatus.FAILED,
                        rawText,
                        textParser.parse(rawText),
                        screenshotPath,
                        MscPageDiagnostics.buildErrorReason(
                                "MSC page returned Access Denied",
                                readPageState(page),
                                null,
                                rawTrackingNumbersForSanitizing),
                        queriedAt);
            }
            if (requiresManualAction(initialText)) {
                String screenshotPath = safeScreenshot(page, screenshotFile, rawTrackingNumbersForSanitizing);
                String rawText = MscTrackingSanitizer.sanitize(initialText, rawTrackingNumbersForSanitizing);
                return MscTrackingResult.of(
                        trackingNoMasked,
                        queryType,
                        MscTrackingStatus.MANUAL_REQUIRED,
                        rawText,
                        textParser.parse(rawText),
                        screenshotPath,
                        "Page requires manual confirmation before query.",
                        queriedAt);
            }

            boolean submittedWithForm = fillAndSubmit(page, trackingNo, queryType);
            boolean directUrlFallbackUsed = false;
            if (!submittedWithForm) {
                String screenshotPath = safeScreenshot(page, screenshotFile, rawTrackingNumbersForSanitizing);
                String rawText = MscTrackingSanitizer.sanitize(initialText, rawTrackingNumbersForSanitizing);
                return MscTrackingResult.of(
                        trackingNoMasked,
                        queryType,
                        MscTrackingStatus.PAGE_ERROR,
                        rawText,
                        textParser.parse(rawText),
                        screenshotPath,
                        MscPageDiagnostics.buildErrorReason(
                                "页面打开但查询输入框未找到",
                                readPageState(page),
                                null,
                                rawTrackingNumbersForSanitizing),
                        queriedAt);
            }

            waitForResultOrPageChange(page, trackingNo, initialText);
            String rawTextBeforeSanitizing = readVisibleText(page);
            if (submittedWithForm && isLikelyUnchanged(initialText, rawTextBeforeSanitizing)) {
                navigateToDirectTrackingUrl(page, trackingNo);
                directUrlFallbackUsed = true;
                waitForResultOrPageChange(page, trackingNo, initialText);
                rawTextBeforeSanitizing = readVisibleText(page);
            }
            String sanitizedText = MscTrackingSanitizer.sanitize(
                    rawTextBeforeSanitizing,
                    rawTrackingNumbersForSanitizing);
            MscTrackingParsedFields parsedFields = textParser.parse(sanitizedText);
            MscTrackingStatus status = classify(rawTextBeforeSanitizing, parsedFields);
            String errorReason = MscPageDiagnostics.isAccessDenied(rawTextBeforeSanitizing)
                    ? MscPageDiagnostics.buildErrorReason(
                            "MSC page returned Access Denied",
                            readPageState(page),
                            null,
                            rawTrackingNumbersForSanitizing)
                    : errorReason(status, submittedWithForm, directUrlFallbackUsed);
            String screenshotPath = safeScreenshot(page, screenshotFile, rawTrackingNumbersForSanitizing);

            return MscTrackingResult.of(
                    trackingNoMasked,
                    queryType,
                    status,
                    sanitizedText,
                    parsedFields,
                    screenshotPath,
                    errorReason,
                    queriedAt);
        } catch (Exception error) {
            String rawText = safeReadVisibleText(page, rawTrackingNumbersForSanitizing);
            String screenshotPath = safeScreenshot(page, screenshotFile, rawTrackingNumbersForSanitizing);
            String errorReason = MscPageDiagnostics.buildErrorReason(
                    "MSC tracking query failed",
                    readPageState(page),
                    error,
                    rawTrackingNumbersForSanitizing);
            return MscTrackingResult.of(
                    trackingNoMasked,
                    queryType,
                    MscTrackingStatus.FAILED,
                    rawText,
                    textParser.parse(rawText),
                    screenshotPath,
                    errorReason,
                    queriedAt);
        } finally {
            page.close();
        }
    }

    @Override
    public void close() {
        context.close();
        browser.close();
        playwright.close();
    }

    private static Browser launchVisibleBrowser(Playwright playwright) {
        BrowserType.LaunchOptions chromeOptions = new BrowserType.LaunchOptions()
                .setHeadless(false)
                .setChannel("chrome")
                .setTimeout(60_000);
        try {
            return playwright.chromium().launch(chromeOptions);
        } catch (RuntimeException chromeError) {
            BrowserType.LaunchOptions chromiumOptions = new BrowserType.LaunchOptions()
                    .setHeadless(false)
                    .setTimeout(60_000);
            return playwright.chromium().launch(chromiumOptions);
        }
    }

    private static void navigateToTrackingPage(Page page) {
        navigateToPage(page, TRACKING_URL);
        waitForTrackingPageReady(page);
        waitForSoftNetworkIdle(page);
    }

    private static void navigateToDirectTrackingUrl(Page page, String trackingNo) {
        navigateToPage(page, TRACKING_URL + "?tnumber=" + urlEncode(trackingNo));
        waitForSoftNetworkIdle(page);
    }

    private static void navigateToPage(Page page, String url) {
        try {
            page.navigate(url, new Page.NavigateOptions()
                    .setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
                    .setTimeout(PAGE_NAVIGATION_TIMEOUT_MS));
            return;
        } catch (RuntimeException firstError) {
            MscPageDiagnostics.PageState state = readPageState(page);
            LOGGER.warn("MSC page navigation did not reach DOMCONTENTLOADED in time; title={}",
                    state.title(),
                    firstError);
            if (MscPageDiagnostics.hasAnyPageSignal(state)) {
                return;
            }

            try {
                page.navigate(url, new Page.NavigateOptions()
                        .setWaitUntil(WaitUntilState.COMMIT)
                        .setTimeout(PAGE_NAVIGATION_TIMEOUT_MS));
            } catch (RuntimeException fallbackError) {
                fallbackError.addSuppressed(firstError);
                throw fallbackError;
            }
        }
    }

    private static void waitForTrackingPageReady(Page page) {
        long deadline = System.currentTimeMillis() + PAGE_READY_TIMEOUT_MS;
        RuntimeException lastReadError = null;
        while (System.currentTimeMillis() < deadline) {
            MscPageDiagnostics.PageState state = readPageState(page);
            if (MscPageDiagnostics.isAccessDenied(state.title())
                    || MscPageDiagnostics.isAccessDenied(state.bodyText())) {
                throw new IllegalStateException("MSC page returned Access Denied.");
            }
            if (MscPageDiagnostics.isPageUsable(state)) {
                return;
            }
            try {
                page.waitForTimeout(1_000);
            } catch (RuntimeException error) {
                lastReadError = error;
            }
        }
        throw new IllegalStateException("MSC tracking page did not become ready.", lastReadError);
    }

    private static boolean fillAndSubmit(Page page, String trackingNo, MscTrackingQueryType queryType) {
        selectQueryType(page, queryType);
        Locator input = findTrackingInput(page);
        if (input == null) {
            return false;
        }

        input.scrollIntoViewIfNeeded(new Locator.ScrollIntoViewIfNeededOptions().setTimeout(5_000));
        input.click(new Locator.ClickOptions().setTimeout(5_000));
        input.fill("", new Locator.FillOptions().setTimeout(5_000));
        input.fill(trackingNo, new Locator.FillOptions().setTimeout(10_000));

        input.press("Enter", new Locator.PressOptions().setTimeout(5_000));
        return true;
    }

    private static void selectQueryType(Page page, MscTrackingQueryType queryType) {
        if (queryType == null) {
            return;
        }
        try {
            page.evaluate("""
                    labelText => {
                      const candidates = Array.from(document.querySelectorAll('label, [role="radio"], span, div'))
                        .filter(element => (element.textContent || '').trim() === labelText)
                        .filter(element => {
                          const rect = element.getBoundingClientRect();
                          return rect.width > 0 && rect.height > 0 && rect.top >= 0 && rect.top < 500;
                        });
                      const target = candidates[candidates.length - 1];
                      if (!target) return false;
                      target.click();
                      return true;
                    }
                    """, queryType.chineseLabel());
            page.waitForTimeout(500);
        } catch (RuntimeException ignored) {
            // If the radio label cannot be clicked, continue and preserve the screenshot/error path.
        }
    }

    private static Locator findTrackingInput(Page page) {
        Locator candidates = page.locator("""
                textarea:visible,
                input:visible:not([type="hidden"]):not([type="checkbox"]):not([type="radio"]):not([type="submit"]):not([type="button"]):not([type="file"]),
                [contenteditable="true"]:visible,
                [role="textbox"]:visible
                """);
        int count = candidates.count();
        int bestIndex = -1;
        int bestScore = 0;
        for (int i = 0; i < count; i++) {
            Locator candidate = candidates.nth(i);
            int score = scoreInputCandidate(readElementContext(candidate));
            if (score > bestScore) {
                bestScore = score;
                bestIndex = i;
            }
        }
        return bestIndex < 0 ? null : candidates.nth(bestIndex);
    }

    private static boolean clickTrackingSearchButton(Page page) {
        Locator candidates = page.locator("""
                button:visible,
                input[type="submit"]:visible,
                [role="button"]:visible,
                a:visible
                """);
        int count = candidates.count();
        int bestIndex = -1;
        int bestScore = 0;
        for (int i = 0; i < count; i++) {
            Locator candidate = candidates.nth(i);
            int score = scoreButtonCandidate(readElementContext(candidate));
            if (score > bestScore) {
                bestScore = score;
                bestIndex = i;
            }
        }
        if (bestIndex < 0) {
            return false;
        }
        candidates.nth(bestIndex).click(new Locator.ClickOptions().setTimeout(10_000));
        return true;
    }

    private static int scoreInputCandidate(String context) {
        String normalized = context.toLowerCase(Locale.ROOT);
        int score = 0;
        score += containsAny(normalized, "集装箱", "提单", "订舱", "货件", "container", "bill of lading", "booking", "shipment", "tracking") ? 40 : 0;
        score += containsAny(normalized, "search", "track", "查询", "搜索", "追踪") ? 10 : 0;
        score -= containsAny(normalized, "newsletter", "subscribe", "email", "mail", "电子邮件", "邮箱", "first name", "last name", "office", "local office", "country-location") ? 50 : 0;
        return score;
    }

    private static int scoreButtonCandidate(String context) {
        String normalized = context.toLowerCase(Locale.ROOT);
        int score = 0;
        score += containsAny(normalized, "搜索", "查询", "追踪", "track", "search") ? 30 : 0;
        score += containsAny(normalized, "集装箱", "提单", "订舱", "container", "bill of lading", "booking", "shipment") ? 20 : 0;
        score -= containsAny(normalized, "newsletter", "subscribe", "email", "mail", "电子邮件", "office", "country-location") ? 40 : 0;
        return score;
    }

    private static String readElementContext(Locator locator) {
        try {
            Object value = locator.evaluate("""
                    element => {
                      const rect = element.getBoundingClientRect();
                      const attrs = [
                        element.innerText,
                        element.value,
                        element.placeholder,
                        element.getAttribute('aria-label'),
                        element.getAttribute('name'),
                        element.getAttribute('id'),
                        element.getAttribute('type')
                      ].filter(Boolean).join(' ');
                      let node = element;
                      let context = attrs;
                      for (let i = 0; i < 4 && node; i++) {
                        context += ' ' + (node.innerText || '');
                        node = node.parentElement;
                      }
                      return `${Math.round(rect.top)} ${context}`.slice(0, 2000);
                    }
                    """);
            return value == null ? "" : String.valueOf(value);
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    private static boolean containsAny(String text, String... needles) {
        for (String needle : needles) {
            if (text.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static void waitForResultOrPageChange(Page page, String trackingNo, String initialText) {
        String normalizedInitialText = normalizeText(initialText);
        long deadline = System.currentTimeMillis() + 45_000;
        while (System.currentTimeMillis() < deadline) {
            page.waitForTimeout(1_000);
            String currentText = readVisibleText(page);
            String lowerText = currentText.toLowerCase(Locale.ROOT);
            if (requiresManualAction(currentText)
                    || containsNoResult(currentText)
                    || lowerText.contains(trackingNo.toLowerCase(Locale.ROOT))
                    || !normalizeText(currentText).equals(normalizedInitialText)) {
                waitForSoftNetworkIdle(page);
                return;
            }
        }
    }

    private static MscTrackingStatus classify(String rawText, MscTrackingParsedFields parsedFields) {
        if (MscPageDiagnostics.isAccessDenied(rawText)) {
            return MscTrackingStatus.FAILED;
        }
        if (requiresManualAction(rawText)) {
            return MscTrackingStatus.MANUAL_REQUIRED;
        }
        if (containsNoResult(rawText)) {
            return MscTrackingStatus.NO_RESULT;
        }
        if (parsedFields.hasAnyValue()) {
            return MscTrackingStatus.SUCCESS;
        }
        if (rawText == null || rawText.isBlank()) {
            return MscTrackingStatus.PAGE_ERROR;
        }
        return MscTrackingStatus.PAGE_ERROR;
    }

    private static String errorReason(MscTrackingStatus status, boolean submittedWithForm, boolean directUrlFallbackUsed) {
        return switch (status) {
            case SUCCESS -> "";
            case NO_RESULT -> "MSC page returned no visible result for this tracking number.";
            case MANUAL_REQUIRED -> "Page requires manual confirmation.";
            case PAGE_ERROR -> {
                if (!submittedWithForm) {
                    yield "Unable to find the tracking input; tried the public tnumber URL fallback.";
                }
                if (directUrlFallbackUsed) {
                    yield "Submitted the form, then tried the public tnumber URL fallback, but no successful result was identified.";
                }
                yield "Unable to identify a successful result from the page text.";
            }
            case FAILED -> "Query failed with an exception.";
        };
    }

    private static boolean requiresManualAction(String text) {
        String lowerText = normalizeText(text).toLowerCase(Locale.ROOT);
        return containsAny(
                lowerText,
                "captcha",
                "verify you are human",
                "human verification",
                "manual confirmation",
                "人机验证",
                "人工确认",
                "验证码",
                "安全检查");
    }

    private static boolean containsNoResult(String text) {
        String lowerText = normalizeText(text).toLowerCase(Locale.ROOT);
        return containsAny(
                lowerText,
                "no result",
                "no results",
                "no matching",
                "not found",
                "未找到",
                "没有找到",
                "暂无结果",
                "无结果");
    }

    private static void dismissCookieBanners(Page page) {
        List<String> buttonSelectors = List.of(
                "button:has-text(\"拒绝所有 Cookie\")",
                "button:has-text(\"Reject All\")",
                "button:has-text(\"接受所有 Cookie\")",
                "button:has-text(\"全部接受\")",
                "button:has-text(\"接受全部\")",
                "button:has-text(\"Accept All\")",
                "button:has-text(\"同意\")",
                "button:has-text(\"Accept\")",
                "#onetrust-reject-all-handler",
                "#onetrust-accept-btn-handler");
        for (String selector : buttonSelectors) {
            Locator button = page.locator(selector).first();
            try {
                if (button.isVisible()) {
                    button.click(new Locator.ClickOptions().setTimeout(2_000));
                    page.waitForTimeout(500);
                    return;
                }
            } catch (RuntimeException ignored) {
                // Cookie banners are non-critical for this PoC.
            }
        }
    }

    private static boolean isLikelyUnchanged(String initialText, String currentText) {
        return normalizeText(initialText).equals(normalizeText(currentText));
    }

    private static String readVisibleText(Page page) {
        return page.locator("body").innerText(new Locator.InnerTextOptions().setTimeout(5_000));
    }

    private static String safeReadVisibleText(Page page, List<String> rawTrackingNumbersForSanitizing) {
        try {
            return MscTrackingSanitizer.sanitize(readVisibleText(page), rawTrackingNumbersForSanitizing);
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    private static MscPageDiagnostics.PageState readPageState(Page page) {
        return new MscPageDiagnostics.PageState(
                safePageUrl(page),
                safePageTitle(page),
                safeRawVisibleText(page));
    }

    private static String safePageUrl(Page page) {
        try {
            return page.url();
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    private static String safePageTitle(Page page) {
        try {
            return page.title();
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    private static String safeRawVisibleText(Page page) {
        try {
            return readVisibleText(page);
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    private static String safeScreenshot(Page page, Path screenshotFile, List<String> rawTrackingNumbersForSanitizing) {
        try {
            Files.createDirectories(screenshotFile.toAbsolutePath().getParent());
            maskTrackingNumbersOnPage(page, rawTrackingNumbersForSanitizing);
            page.screenshot(new Page.ScreenshotOptions()
                    .setPath(screenshotFile)
                    .setFullPage(true)
                    .setTimeout(15_000));
            return screenshotFile.toAbsolutePath().toString();
        } catch (RuntimeException | IOException ignored) {
            return "";
        }
    }

    private static void maskTrackingNumbersOnPage(Page page, List<String> rawTrackingNumbersForSanitizing) {
        List<List<String>> replacements = rawTrackingNumbersForSanitizing.stream()
                .filter(number -> number != null && !number.isBlank())
                .map(number -> List.of(number, MscTrackingSanitizer.mask(number)))
                .toList();
        if (replacements.isEmpty()) {
            return;
        }
        try {
            page.evaluate("""
                    replacements => {
                      const replaceAll = value => {
                        let next = value || '';
                        for (const [raw, masked] of replacements) {
                          next = next.split(raw).join(masked);
                        }
                        next = next.replace(/\\b[A-Z]{4}(?=[A-Z0-9]*\\d)[A-Z0-9]{6,12}\\b/g, token => {
                          if (token.length <= 7) return '****';
                          return token.slice(0, 4) + '****' + token.slice(-3);
                        });
                        return next;
                      };
                      document.querySelectorAll('input, textarea').forEach(element => {
                        if (element.value) {
                          element.value = replaceAll(element.value);
                          element.setAttribute('value', element.value);
                        }
                        if (element.placeholder) {
                          element.placeholder = replaceAll(element.placeholder);
                        }
                      });
                      const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
                      let node = walker.nextNode();
                      while (node) {
                        node.nodeValue = replaceAll(node.nodeValue);
                        node = walker.nextNode();
                      }
                    }
                    """, replacements);
        } catch (RuntimeException ignored) {
            // Screenshot capture should still proceed even if DOM masking fails.
        }
    }

    private static Path screenshotFile(Path screenshotsDir, int sequence, String trackingNoMasked) {
        String safeMaskedNumber = trackingNoMasked.replaceAll("[^A-Za-z0-9]", "_");
        String timestamp = ZonedDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        return screenshotsDir.resolve("%03d_%s_%s.png".formatted(sequence, safeMaskedNumber, timestamp));
    }

    private static void waitForSoftNetworkIdle(Page page) {
        try {
            page.waitForLoadState(LoadState.NETWORKIDLE, new Page.WaitForLoadStateOptions().setTimeout(10_000));
        } catch (TimeoutError ignored) {
            // Some public pages keep analytics requests open; DOM content is enough for this PoC.
        }
    }

    private static String normalizeText(String text) {
        return text == null ? "" : text.replaceAll("\\s+", " ").trim();
    }

    private static String now() {
        return ZonedDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    private static String urlEncode(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
    }
}

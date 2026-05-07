package com.example.myaiproject.tool.msc;

import java.io.IOException;
import java.nio.file.Files;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

public class MscTrackingPocRunner {
    private final MscTrackingInputReader inputReader;
    private final MscTrackingOutputWriter outputWriter;
    private final MscTrackingExcelWriter excelWriter;

    public MscTrackingPocRunner() {
        this(new MscTrackingInputReader(), new MscTrackingOutputWriter(), new MscTrackingExcelWriter());
    }

    MscTrackingPocRunner(
            MscTrackingInputReader inputReader,
            MscTrackingOutputWriter outputWriter,
            MscTrackingExcelWriter excelWriter) {
        this.inputReader = inputReader;
        this.outputWriter = outputWriter;
        this.excelWriter = excelWriter;
    }

    public static void main(String[] args) throws Exception {
        new MscTrackingPocRunner().run(MscTrackingConfig.fromArgs(args));
    }

    void run(MscTrackingConfig config) throws Exception {
        if (!config.enabled()) {
            System.out.println("MSC tracking PoC disabled.");
            return;
        }

        Files.createDirectories(config.outputDir());
        Files.createDirectories(config.screenshotsDir());

        List<String> inputTrackingNumbers = inputReader.read(config.inputFile(), Integer.MAX_VALUE);
        List<String> trackingNumbers = MscTrackingNumberClassifier.filterByPrefix(
                inputTrackingNumbers,
                config.onlyPrefix(),
                config.maxItems());
        System.out.printf(
                "MSC tracking PoC loaded %d tracking numbers from %s. Max items: %d. Prefix filter: %s.%n",
                trackingNumbers.size(),
                config.inputFile().toAbsolutePath(),
                config.maxItems(),
                config.onlyPrefix().isBlank() ? "(none)" : config.onlyPrefix());
        if (trackingNumbers.isEmpty()) {
            writeOutput(config, List.of(), trackingNumbers);
            return;
        }

        List<MscTrackingResult> results = new ArrayList<>();
        try (MscBrowserTracker tracker = new MscBrowserTracker()) {
            for (int i = 0; i < trackingNumbers.size(); i++) {
                String trackingNo = trackingNumbers.get(i);
                String trackingNoMasked = MscTrackingSanitizer.mask(trackingNo);
                MscTrackingQueryType queryType = MscTrackingNumberClassifier.classify(trackingNo);
                int sequence = i + 1;
                System.out.printf(
                        "Querying MSC tracking number %d/%d: %s (%s)%n",
                        sequence,
                        trackingNumbers.size(),
                        trackingNoMasked,
                        queryType.chineseLabel());

                MscTrackingResult result = tracker.query(
                        trackingNo,
                        queryType,
                        sequence,
                        config.screenshotsDir(),
                        inputTrackingNumbers);
                results.add(result);
                writeOutput(config, results, inputTrackingNumbers);

                System.out.printf(
                        "Result %d/%d for %s: %s. Screenshot: %s%n",
                        sequence,
                        trackingNumbers.size(),
                        trackingNoMasked,
                        result.status(),
                        result.screenshotPath());

                if (i < trackingNumbers.size() - 1) {
                    sleepBetweenQueries(config.delayMin(), config.delayMax(), trackingNoMasked);
                }
            }
        }

        writeOutput(config, results, inputTrackingNumbers);
        MscTrackingSummary summary = MscTrackingSummary.from(results);
        System.out.printf(
                "MSC tracking PoC finished. Total=%d, Success=%d, Failed=%d. Output: %s. Excel: %s%n",
                summary.total(),
                summary.success(),
                summary.failed(),
                config.outputFile().toAbsolutePath(),
                config.excelOutputFile().toAbsolutePath());
    }

    private void writeOutput(
            MscTrackingConfig config,
            List<MscTrackingResult> results,
            List<String> rawTrackingNumbersForSanitizing) throws IOException {
        MscTrackingOutput output = new MscTrackingOutput(
                MscBrowserTracker.TRACKING_URL,
                config.inputFile().toAbsolutePath().toString(),
                config.maxItems(),
                ZonedDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                MscTrackingSummary.from(results),
                results.stream()
                        .map(result -> sanitizeResult(result, rawTrackingNumbersForSanitizing))
                        .toList());
        List<MscTrackingResult> sanitizedResults = output.results();
        outputWriter.write(config.outputFile(), output);
        excelWriter.write(config.excelOutputFile(), sanitizedResults);
    }

    private static MscTrackingResult sanitizeResult(
            MscTrackingResult result,
            List<String> rawTrackingNumbersForSanitizing) {
        MscTrackingParsedFields parsedFields = new MscTrackingParsedFields(
                MscTrackingSanitizer.sanitize(result.parsedCurrentStatus(), rawTrackingNumbersForSanitizing),
                MscTrackingSanitizer.sanitize(result.parsedEta(), rawTrackingNumbersForSanitizing),
                MscTrackingSanitizer.sanitize(result.parsedLatestNode(), rawTrackingNumbersForSanitizing));
        return MscTrackingResult.of(
                result.trackingNoMasked(),
                queryTypeFromChineseLabel(result.queryType()),
                result.status(),
                MscTrackingSanitizer.sanitize(result.rawText(), rawTrackingNumbersForSanitizing),
                parsedFields,
                result.screenshotPath(),
                MscTrackingSanitizer.sanitize(result.errorReason(), rawTrackingNumbersForSanitizing),
                result.queriedAt());
    }

    private static MscTrackingQueryType queryTypeFromChineseLabel(String label) {
        if (MscTrackingQueryType.BOOKING.chineseLabel().equals(label)) {
            return MscTrackingQueryType.BOOKING;
        }
        return MscTrackingQueryType.CONTAINER_OR_BOL;
    }

    private static void sleepBetweenQueries(Duration delayMin, Duration delayMax, String trackingNoMasked) throws InterruptedException {
        long minMillis = delayMin.toMillis();
        long maxMillis = delayMax.toMillis();
        long sleepMillis = minMillis == maxMillis
                ? minMillis
                : ThreadLocalRandom.current().nextLong(minMillis, maxMillis + 1);
        System.out.printf("Waiting %.1f seconds before next query after %s.%n", sleepMillis / 1000.0, trackingNoMasked);
        Thread.sleep(sleepMillis);
    }
}

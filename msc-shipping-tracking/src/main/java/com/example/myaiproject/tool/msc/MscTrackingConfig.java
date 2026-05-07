package com.example.myaiproject.tool.msc;

import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

public record MscTrackingConfig(
        boolean enabled,
        Path inputFile,
        Path outputDir,
        int maxItems,
        String onlyPrefix,
        Duration delayMin,
        Duration delayMax) {

    static MscTrackingConfig fromArgs(String[] args) {
        Map<String, String> values = parseArgs(args);
        boolean enabled = Boolean.parseBoolean(values.getOrDefault("msc.tracking.poc", "true"));
        Path inputFile = Path.of(values.getOrDefault("msc.tracking.input-file", "/Users/huangkailun/Desktop/单号.json"));
        Path outputDir = Path.of(values.getOrDefault("msc.tracking.output-dir", "data/msc-tracking"));
        int maxItems = Integer.parseInt(values.getOrDefault("msc.tracking.max-items", "5"));
        String onlyPrefix = values.getOrDefault("msc.tracking.only-prefix", "").trim();
        long delayMinSeconds = Long.parseLong(values.getOrDefault("msc.tracking.delay-min-seconds", "10"));
        long delayMaxSeconds = Long.parseLong(values.getOrDefault("msc.tracking.delay-max-seconds", "20"));
        if (delayMinSeconds < 0 || delayMaxSeconds < 0) {
            throw new IllegalArgumentException("Delay seconds must be zero or positive.");
        }
        if (delayMaxSeconds < delayMinSeconds) {
            throw new IllegalArgumentException("Delay max seconds must be greater than or equal to delay min seconds.");
        }
        return new MscTrackingConfig(
                enabled,
                inputFile,
                outputDir,
                Math.max(0, maxItems),
                onlyPrefix,
                Duration.ofSeconds(delayMinSeconds),
                Duration.ofSeconds(delayMaxSeconds));
    }

    Path outputFile() {
        return outputDir.resolve("output.json");
    }

    Path excelOutputFile() {
        return outputDir.resolve("output.xlsx");
    }

    Path screenshotsDir() {
        return outputDir.resolve("screenshots");
    }

    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> values = new HashMap<>();
        for (String arg : args) {
            if (!arg.startsWith("--")) {
                continue;
            }
            String body = arg.substring(2);
            int separator = body.indexOf('=');
            if (separator < 0) {
                values.put(body, "true");
            } else {
                values.put(body.substring(0, separator), body.substring(separator + 1));
            }
        }
        return values;
    }
}

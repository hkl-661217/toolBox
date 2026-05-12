package com.example.myaiproject.shipping.client;

import com.example.myaiproject.shipping.model.ShippingTrackingEvent;
import com.example.myaiproject.shipping.service.ShippingTrackingProperties;
import com.example.myaiproject.tool.msc.MscTrackingStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Talks to MSC's TrackingInfo JSON API through a Python sidecar that uses
 * curl_cffi to impersonate Chrome's TLS fingerprint. The sidecar handles
 * the homepage warm-up + API POST in a single short-lived process; this
 * class owns argv/stdin/stdout marshalling.
 */
@Component
@ConditionalOnProperty(
        name = "shipping.tracking.client",
        havingValue = "curl-impersonate",
        matchIfMissing = true)
public class CurlImpersonateMscTrackingClient implements MscTrackingClient {
    private static final Logger LOGGER = LoggerFactory.getLogger(CurlImpersonateMscTrackingClient.class);

    private final ShippingTrackingProperties properties;
    private final ObjectMapper objectMapper;
    private final ObjectMapper prettyMapper;

    public CurlImpersonateMscTrackingClient(ShippingTrackingProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.prettyMapper = objectMapper.copy().enable(SerializationFeature.INDENT_OUTPUT);
    }

    @Override
    public MscTrackingQueryResult queryBooking(String bookingNo) {
        OffsetDateTime queryTime = OffsetDateTime.now();
        try {
            JsonNode response = runSidecar(bookingNo);
            return interpret(response, queryTime);
        } catch (Exception error) {
            LOGGER.warn("curl-impersonate sidecar invocation failed", error);
            return failure(queryTime, "sidecar invocation failed: " + error.getMessage());
        }
    }

    private JsonNode runSidecar(String bookingNo) throws IOException, InterruptedException {
        Path scriptPath = resolveScript();
        ProcessBuilder pb = new ProcessBuilder(
                properties.getPythonExecutable(),
                scriptPath.toAbsolutePath().toString());
        pb.redirectErrorStream(true);

        Process process = pb.start();

        try (OutputStream stdin = process.getOutputStream()) {
            Map<String, String> request = new LinkedHashMap<>();
            request.put("trackingNumber", bookingNo);
            request.put("trackingMode", "1");
            stdin.write(objectMapper.writeValueAsBytes(request));
        }

        long timeoutMs = properties.getCurlImpersonateTimeoutMs();
        boolean exited = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
        if (!exited) {
            process.destroyForcibly();
            throw new IOException("sidecar timed out after " + timeoutMs + "ms");
        }

        byte[] stdoutBytes = process.getInputStream().readAllBytes();
        String stdout = new String(stdoutBytes, StandardCharsets.UTF_8).trim();
        if (stdout.isEmpty()) {
            throw new IOException("sidecar produced empty stdout (exit=" + process.exitValue() + ")");
        }
        return objectMapper.readTree(stdout);
    }

    private Path resolveScript() throws IOException {
        Path configured = Path.of(properties.getPythonScriptPath());
        if (Files.isRegularFile(configured)) {
            return configured;
        }
        Path absolute = configured.toAbsolutePath();
        if (Files.isRegularFile(absolute)) {
            return absolute;
        }
        throw new IOException("python script not found at " + configured + " (resolved " + absolute + ")");
    }

    private MscTrackingQueryResult interpret(JsonNode response, OffsetDateTime queryTime) throws IOException {
        String statusText = textOrEmpty(response, "status");
        String errorReason = textOrEmpty(response, "errorReason");
        JsonNode data = response.get("data");

        switch (statusText) {
            case "SUCCESS" -> {
                if (data == null || data.isNull()) {
                    return failure(queryTime, "sidecar reported SUCCESS but data is null");
                }
                return parseSuccess(data, queryTime);
            }
            case "NO_RESULT" -> {
                return new MscTrackingQueryResult(
                        MscTrackingStatus.NO_RESULT,
                        errorReason,
                        "",
                        null,
                        null,
                        List.of(),
                        "",
                        errorReason,
                        queryTime);
            }
            case "FAILED", "" -> {
                return failure(queryTime, errorReason.isBlank() ? "sidecar returned FAILED without reason" : errorReason);
            }
            default -> {
                return failure(queryTime, "sidecar returned unknown status: " + statusText);
            }
        }
    }

    private MscTrackingQueryResult parseSuccess(JsonNode data, OffsetDateTime queryTime) throws IOException {
        String currentStatus = extractCurrentStatus(data);
        String eta = extractEta(data);
        List<ShippingTrackingEvent> events = extractEvents(data);
        String latestNode = extractLatestNode(events);
        String rawText = prettyMapper.writeValueAsString(data);

        return new MscTrackingQueryResult(
                MscTrackingStatus.SUCCESS,
                rawText,
                currentStatus,
                eta,
                latestNode,
                events,
                "",
                "",
                queryTime);
    }

    private MscTrackingQueryResult failure(OffsetDateTime queryTime, String reason) {
        return new MscTrackingQueryResult(
                MscTrackingStatus.FAILED,
                "",
                "",
                null,
                null,
                List.of(),
                "",
                reason,
                queryTime);
    }

    private static String extractCurrentStatus(JsonNode data) {
        JsonNode firstBol = firstBillOfLading(data);
        if (firstBol == null) {
            return "";
        }
        JsonNode general = firstBol.path("GeneralTrackingInfo");
        String from = textOrEmpty(general, "ShippedFrom");
        String to = textOrEmpty(general, "ShippedTo");
        String number = textOrEmpty(firstBol, "BillOfLadingNumber");
        StringBuilder sb = new StringBuilder();
        if (!number.isBlank()) {
            sb.append("BL ").append(number);
        }
        if (!from.isBlank() || !to.isBlank()) {
            if (sb.length() > 0) {
                sb.append(" | ");
            }
            sb.append(from).append(" -> ").append(to);
        }
        return sb.toString();
    }

    private static String extractEta(JsonNode data) {
        JsonNode firstBol = firstBillOfLading(data);
        if (firstBol == null) {
            return null;
        }
        String eta = textOrEmpty(firstBol.path("GeneralTrackingInfo"), "FinalPodEtaDate");
        return eta.isBlank() ? null : eta;
    }

    private static List<ShippingTrackingEvent> extractEvents(JsonNode data) {
        List<ShippingTrackingEvent> events = new ArrayList<>();
        JsonNode bols = data.path("BillOfLadings");
        if (!bols.isArray()) {
            return events;
        }
        for (JsonNode bol : bols) {
            JsonNode containers = bol.path("ContainersInfo");
            if (!containers.isArray()) {
                continue;
            }
            for (JsonNode container : containers) {
                JsonNode rawEvents = container.path("Events");
                if (!rawEvents.isArray()) {
                    continue;
                }
                for (JsonNode event : rawEvents) {
                    events.add(new ShippingTrackingEvent(
                            textOrEmpty(event, "Date"),
                            textOrEmpty(event, "Location"),
                            textOrEmpty(event, "Description"),
                            joinDetail(event.path("Detail"))));
                }
            }
        }
        return events;
    }

    private static String extractLatestNode(List<ShippingTrackingEvent> events) {
        if (events.isEmpty()) {
            return null;
        }
        ShippingTrackingEvent first = events.get(0);
        String date = first.date() == null ? "" : first.date().trim();
        String location = first.location() == null ? "" : first.location().trim();
        String description = first.description() == null ? "" : first.description().trim();
        if (date.isEmpty() && location.isEmpty() && description.isEmpty()) {
            return null;
        }
        return date + "|" + location + "|" + description;
    }

    private static String joinDetail(JsonNode detail) {
        if (!detail.isArray() || detail.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (JsonNode item : detail) {
            if (item.isTextual()) {
                if (sb.length() > 0) {
                    sb.append(' ');
                }
                sb.append(item.asText());
            }
        }
        return sb.toString();
    }

    private static JsonNode firstBillOfLading(JsonNode data) {
        JsonNode bols = data.path("BillOfLadings");
        if (bols.isArray() && !bols.isEmpty()) {
            return bols.get(0);
        }
        return null;
    }

    private static String textOrEmpty(JsonNode node, String field) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return "";
        }
        JsonNode child = node.get(field);
        return child == null || child.isNull() ? "" : child.asText("");
    }
}

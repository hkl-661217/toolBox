package com.example.myaiproject.shipping.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.myaiproject.shipping.model.ShippingTrackingEvent;
import com.example.myaiproject.shipping.service.ShippingTrackingProperties;
import com.example.myaiproject.tool.msc.MscTrackingStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CurlImpersonateMscTrackingClientTest {

    @TempDir
    Path tmpDir;

    private CurlImpersonateMscTrackingClient clientReturning(String stdoutPayload) throws IOException {
        return clientFor(fakeScript(stdoutPayload, 0));
    }

    private CurlImpersonateMscTrackingClient clientSleeping(int sleepSeconds, long timeoutMs) throws IOException {
        Path script = tmpDir.resolve("sleep.sh");
        Files.writeString(script, "#!/bin/bash\ncat > /dev/null\nsleep " + sleepSeconds + "\n");
        Files.setPosixFilePermissions(script, java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"));
        ShippingTrackingProperties props = baseProps(script);
        props.setCurlImpersonateTimeoutMs(timeoutMs);
        return new CurlImpersonateMscTrackingClient(props, new ObjectMapper());
    }

    private CurlImpersonateMscTrackingClient clientFor(Path script) {
        return new CurlImpersonateMscTrackingClient(baseProps(script), new ObjectMapper());
    }

    private Path fakeScript(String payload, int exitCode) throws IOException {
        Path script = tmpDir.resolve("fake-" + Math.abs(payload.hashCode()) + ".sh");
        StringBuilder sb = new StringBuilder();
        sb.append("#!/bin/bash\n");
        sb.append("cat > /dev/null\n");
        sb.append("cat <<'PAYLOAD_EOF'\n");
        sb.append(payload);
        if (!payload.endsWith("\n")) {
            sb.append('\n');
        }
        sb.append("PAYLOAD_EOF\n");
        sb.append("exit ").append(exitCode).append('\n');
        Files.writeString(script, sb.toString());
        Files.setPosixFilePermissions(script, java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"));
        return script;
    }

    private ShippingTrackingProperties baseProps(Path script) {
        ShippingTrackingProperties props = new ShippingTrackingProperties();
        props.setPythonExecutable("/bin/bash");
        props.setPythonScriptPath(script.toAbsolutePath().toString());
        props.setCurlImpersonateTimeoutMs(5_000);
        return props;
    }

    @Test
    void successResponseMapsToShippingTrackingResult() throws IOException {
        String payload = """
                {
                  "status": "SUCCESS",
                  "errorReason": "",
                  "data": {
                    "TrackingType": "Booking Number",
                    "TrackingNumber": "BKG123",
                    "BillOfLadings": [
                      {
                        "BillOfLadingNumber": "BL-XYZ-1",
                        "NumberOfContainers": 1,
                        "GeneralTrackingInfo": {
                          "ShippedFrom": "QINGDAO, CN",
                          "ShippedTo": "SYDNEY, AU",
                          "FinalPodEtaDate": "31/05/2026"
                        },
                        "ContainersInfo": [
                          {
                            "Events": [
                              {
                                "Order": 3,
                                "Date": "31/05/2026",
                                "Location": "SYDNEY, AU",
                                "Description": "Estimated Time of Arrival",
                                "Detail": ["MSC MELANI III", "SE622R"]
                              },
                              {
                                "Order": 2,
                                "Date": "07/05/2026",
                                "Location": "QINGDAO, CN",
                                "Description": "Export Loaded on Vessel",
                                "Detail": ["MSC MELANI III", "SE619A"]
                              }
                            ]
                          }
                        ]
                      }
                    ]
                  }
                }
                """;

        MscTrackingQueryResult result = clientReturning(payload).queryBooking("BKG123");

        assertEquals(MscTrackingStatus.SUCCESS, result.status());
        assertEquals("31/05/2026", result.eta());
        assertEquals(2, result.events().size());
        ShippingTrackingEvent first = result.events().get(0);
        assertEquals("31/05/2026", first.date());
        assertEquals("SYDNEY, AU", first.location());
        assertEquals("Estimated Time of Arrival", first.description());
        assertEquals("MSC MELANI III SE622R", first.vesselVoyage());
        assertTrue(result.currentStatus().contains("BL BL-XYZ-1"));
        assertTrue(result.currentStatus().contains("QINGDAO, CN -> SYDNEY, AU"));
        assertNotNull(result.latestNode());
        assertEquals("31/05/2026|SYDNEY, AU|Estimated Time of Arrival", result.latestNode());
        assertTrue(result.rawText().contains("MEDUWX".isEmpty() ? "BL-XYZ-1" : "BL-XYZ-1"));
        assertEquals("", result.errorReason());
    }

    @Test
    void noResultStatusPassesThroughErrorReasonAndNullsBusinessFields() throws IOException {
        String payload = """
                {"status": "NO_RESULT", "errorReason": "暂时不可用", "data": null}
                """;

        MscTrackingQueryResult result = clientReturning(payload).queryBooking("BKG_EMPTY");

        assertEquals(MscTrackingStatus.NO_RESULT, result.status());
        assertEquals("暂时不可用", result.errorReason());
        assertNull(result.eta());
        assertNull(result.latestNode());
        assertEquals(List.of(), result.events());
    }

    @Test
    void failedStatusPreservesErrorReason() throws IOException {
        String payload = """
                {"status": "FAILED", "errorReason": "API HTTP 503; body head: <html>...", "data": null}
                """;

        MscTrackingQueryResult result = clientReturning(payload).queryBooking("X");

        assertEquals(MscTrackingStatus.FAILED, result.status());
        assertTrue(result.errorReason().startsWith("API HTTP 503"));
        assertEquals(List.of(), result.events());
    }

    @Test
    void emptyStdoutBecomesFailedWithDescriptiveError() throws IOException {
        Path script = tmpDir.resolve("empty.sh");
        Files.writeString(script, "#!/bin/bash\ncat > /dev/null\n");
        Files.setPosixFilePermissions(script, java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"));

        MscTrackingQueryResult result = clientFor(script).queryBooking("X");

        assertEquals(MscTrackingStatus.FAILED, result.status());
        assertTrue(result.errorReason().contains("empty stdout"));
    }

    @Test
    void missingScriptPathBecomesFailed() {
        ShippingTrackingProperties props = new ShippingTrackingProperties();
        props.setPythonExecutable("/bin/bash");
        props.setPythonScriptPath("/tmp/definitely-not-a-real-path-" + System.nanoTime() + ".sh");
        props.setCurlImpersonateTimeoutMs(5_000);

        MscTrackingQueryResult result = new CurlImpersonateMscTrackingClient(props, new ObjectMapper())
                .queryBooking("X");

        assertEquals(MscTrackingStatus.FAILED, result.status());
        assertTrue(result.errorReason().toLowerCase().contains("not found"));
    }

    @Test
    void sidecarTimeoutBecomesFailed() throws IOException {
        MscTrackingQueryResult result = clientSleeping(5, 500).queryBooking("X");

        assertEquals(MscTrackingStatus.FAILED, result.status());
        assertTrue(result.errorReason().contains("timed out"));
    }

    @Test
    void unknownStatusBecomesFailed() throws IOException {
        String payload = """
                {"status": "WAT", "errorReason": "", "data": null}
                """;

        MscTrackingQueryResult result = clientReturning(payload).queryBooking("X");

        assertEquals(MscTrackingStatus.FAILED, result.status());
        assertTrue(result.errorReason().contains("WAT"));
    }
}

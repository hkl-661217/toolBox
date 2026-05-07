package com.example.myaiproject.tool.msc;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class MscTrackingSanitizerTest {

    @Test
    void masksNormalTrackingNumberWithFirstFourAndLastThreeVisible() {
        assertEquals("MSCU****567", MscTrackingSanitizer.mask("MSCU1234567"));
    }

    @Test
    void masksShortTrackingNumberWithoutRevealingIt() {
        assertEquals("****", MscTrackingSanitizer.mask("ABC123"));
    }

    @Test
    void replacesRawTrackingNumbersInText() {
        String sanitized = MscTrackingSanitizer.sanitize(
                "Result for MSCU1234567 and MEDU7654321",
                List.of("MSCU1234567", "MEDU7654321"));

        assertEquals("Result for MSCU****567 and MEDU****321", sanitized);
    }

    @Test
    void masksDetectedBillOfLadingAndContainerLikeTokens() {
        String sanitized = MscTrackingSanitizer.sanitize(
                "提单 MEDUWZ031644 集装箱 MEDU3101039 vessel MSC NIMISHA",
                List.of());

        assertEquals("提单 MEDU****644 集装箱 MEDU****039 vessel MSC NIMISHA", sanitized);
    }
}

package com.example.myaiproject.tool.msc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MscTrackingInputReaderTest {

    @TempDir
    Path tempDir;

    @Test
    void readsNumberArrayAndLimitsToRequestedMaximum() throws Exception {
        Path inputFile = tempDir.resolve("numbers.json");
        Files.writeString(inputFile, """
                {
                  "number": ["MSCU1234567", "MEDU7654321", "TCLU1111111", "OOLU2222222", "HDMU3333333", "EXTRA9999999"]
                }
                """);

        List<String> numbers = new MscTrackingInputReader().read(inputFile, 5);

        assertEquals(List.of("MSCU1234567", "MEDU7654321", "TCLU1111111", "OOLU2222222", "HDMU3333333"), numbers);
    }

    @Test
    void rejectsMissingNumberField() throws Exception {
        Path inputFile = tempDir.resolve("numbers.json");
        Files.writeString(inputFile, """
                {
                  "numbers": ["MSCU1234567"]
                }
                """);

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> new MscTrackingInputReader().read(inputFile, 5));

        assertEquals("Input JSON must contain a number array.", error.getMessage());
    }

    @Test
    void rejectsNonArrayNumberField() throws Exception {
        Path inputFile = tempDir.resolve("numbers.json");
        Files.writeString(inputFile, """
                {
                  "number": "MSCU1234567"
                }
                """);

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> new MscTrackingInputReader().read(inputFile, 5));

        assertEquals("Input JSON field number must be an array.", error.getMessage());
    }
}

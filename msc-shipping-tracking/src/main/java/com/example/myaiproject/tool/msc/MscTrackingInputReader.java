package com.example.myaiproject.tool.msc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class MscTrackingInputReader {
    private final ObjectMapper objectMapper;

    public MscTrackingInputReader() {
        this(new ObjectMapper());
    }

    MscTrackingInputReader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public List<String> read(Path inputFile, int maxItems) throws IOException {
        if (maxItems <= 0) {
            return List.of();
        }

        JsonNode root = objectMapper.readTree(inputFile.toFile());
        JsonNode numbers = root.get("number");
        if (numbers == null) {
            throw new IllegalArgumentException("Input JSON must contain a number array.");
        }
        if (!numbers.isArray()) {
            throw new IllegalArgumentException("Input JSON field number must be an array.");
        }

        List<String> trackingNumbers = new ArrayList<>();
        for (JsonNode numberNode : numbers) {
            if (!numberNode.isTextual()) {
                throw new IllegalArgumentException("Input JSON field number must contain strings only.");
            }
            String trackingNumber = numberNode.asText().trim();
            if (!trackingNumber.isBlank()) {
                trackingNumbers.add(trackingNumber);
            }
            if (trackingNumbers.size() == maxItems) {
                break;
            }
        }
        return trackingNumbers;
    }
}

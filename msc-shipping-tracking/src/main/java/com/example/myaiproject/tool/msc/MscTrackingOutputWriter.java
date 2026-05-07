package com.example.myaiproject.tool.msc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class MscTrackingOutputWriter {
    private final ObjectMapper objectMapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    public void write(Path outputFile, MscTrackingOutput output) throws IOException {
        Files.createDirectories(outputFile.toAbsolutePath().getParent());
        objectMapper.writeValue(outputFile.toFile(), output);
    }
}

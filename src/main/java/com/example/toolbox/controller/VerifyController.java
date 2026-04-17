package com.example.toolbox.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api")
public class VerifyController {

    private final Set<String> activeCodes;

    public VerifyController(@Value("${toolbox.active-codes:}") String activeCodes) {
        this.activeCodes = Arrays.stream(activeCodes.split(","))
                .map(String::trim)
                .filter(code -> !code.isEmpty())
                .collect(Collectors.toSet());
    }

    @PostMapping("/verify")
    public Map<String, Object> verify(@RequestBody VerifyRequest request) {
        if (request == null || request.code() == null || !activeCodes.contains(request.code().trim())) {
            return Map.of(
                    "success", false,
                    "message", "验证码无效"
            );
        }
        return Map.of("success", true);
    }

    public record VerifyRequest(String code) {
    }
}

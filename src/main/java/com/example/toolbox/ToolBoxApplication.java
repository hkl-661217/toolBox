package com.example.toolbox;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class ToolBoxApplication {
    public static void main(String[] args) {
        SpringApplication.run(ToolBoxApplication.class, args);
    }
}
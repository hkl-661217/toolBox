package com.example.myaiproject;

import com.example.myaiproject.shipping.service.ShippingTrackingProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(ShippingTrackingProperties.class)
public class MyAiProjectApplication {
    public static void main(String[] args) {
        SpringApplication.run(MyAiProjectApplication.class, args);
    }
}

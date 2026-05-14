package com.example.adomock;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import com.example.adomock.config.AdoProperties;

@SpringBootApplication
@EnableConfigurationProperties(AdoProperties.class)
public class AdoMockApplication {
    public static void main(String[] args) {
        SpringApplication.run(AdoMockApplication.class, args);
    }
}

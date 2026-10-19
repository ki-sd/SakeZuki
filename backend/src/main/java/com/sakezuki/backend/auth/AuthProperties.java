package com.sakezuki.backend.auth;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import java.time.Duration;

@Data
@ConfigurationProperties(prefix = "auth")
public class AuthProperties {
    private String frontendUrl = "http://localhost:3000";
    private boolean secureCookies = false;
    private Duration refreshExpiration = Duration.ofDays(30);
    private Duration socialExpiration = Duration.ofMinutes(10);
}

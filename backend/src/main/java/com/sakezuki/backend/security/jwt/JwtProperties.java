package com.sakezuki.backend.security.jwt;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix="jwt")
@Data
public class JwtProperties {
    private String secret;
    private Duration accessTokenExpiration;
}

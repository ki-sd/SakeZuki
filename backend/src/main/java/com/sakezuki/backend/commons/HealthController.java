package com.sakezuki.backend.commons;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController @RequiredArgsConstructor
public class HealthController {
    private final JdbcTemplate database;
    @GetMapping("/api/health/ready")
    public ResponseEntity<?> ready() {
        try {
            database.queryForObject("SELECT 1", Integer.class);
            return ResponseEntity.ok().header("Cache-Control", "no-store").body(Map.of("status", "UP"));
        } catch (RuntimeException error) {
            return ResponseEntity.status(503).header("Cache-Control", "no-store").body(Map.of("status", "DOWN"));
        }
    }
}

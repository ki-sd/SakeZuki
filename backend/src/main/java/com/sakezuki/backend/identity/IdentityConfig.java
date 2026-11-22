package com.sakezuki.backend.identity;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@Configuration
public class IdentityConfig {
    @Bean
    @ConditionalOnMissingBean(IdentityProvider.class)
    IdentityProvider unavailableIdentityProvider() {
        // 계약이 없는 운영 환경은 닫힌 상태로 둔다. 성공하는 Mock 구현은 test 소스에만 존재한다.
        return new IdentityProvider() {
            public boolean available() { return false; }
            public Challenge start(String requestId) { throw unavailable(); }
            public Verified verify(String reference, String proof) { throw unavailable(); }
            private ResponseStatusException unavailable() { return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "본인확인 서비스 연결을 준비 중입니다."); }
        };
    }
}

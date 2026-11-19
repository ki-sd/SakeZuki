package com.sakezuki.backend.auth;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import java.time.Duration;

@Data
@ConfigurationProperties(prefix = "auth")
public class AuthProperties implements org.springframework.beans.factory.InitializingBean {
    private String frontendUrl = "http://localhost:3000";
    private boolean secureCookies = false;
    private Duration refreshExpiration = Duration.ofDays(30);
    private Duration socialExpiration = Duration.ofMinutes(10);
    @Override public void afterPropertiesSet() {
        var uri=java.net.URI.create(frontendUrl);
        if (uri.getHost()==null || uri.getUserInfo()!=null || uri.getQuery()!=null || uri.getFragment()!=null
                || !("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()))
                || !(uri.getPath().isEmpty() || "/".equals(uri.getPath()))
                || (secureCookies && !"https".equals(uri.getScheme())))
            throw new IllegalArgumentException("AUTH_FRONTEND_URL은 올바른 출처여야 하며 Secure 쿠키에는 HTTPS가 필요합니다.");
        // 출처 끝의 슬래시를 제거해 고정 OAuth 복귀 경로와 CORS 설정이 같은 값을 사용하게 한다.
        frontendUrl=frontendUrl.replaceAll("/+$", "");
        if (refreshExpiration.isNegative() || refreshExpiration.isZero() || socialExpiration.isNegative() || socialExpiration.isZero())
            throw new IllegalArgumentException("인증 유효기간은 양수여야 합니다.");
    }
}

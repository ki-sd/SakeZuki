package com.sakezuki.backend.security.oauth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.io.Serializable;
import java.time.Instant;

public record SocialLoginState(SocialSignupDTO signup, Long memberNo, Instant expiresAt) implements Serializable {
    public static final String KEY = "socialLoginState";
    public static SocialLoginState read(HttpServletRequest request, boolean consume) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            synchronized (session) {
                try {
                    Object value = session.getAttribute(KEY);
                    if (value instanceof SocialLoginState state && state.expiresAt.isAfter(Instant.now())) {
                        if (consume) session.removeAttribute(KEY);
                        return state;
                    }
                    session.removeAttribute(KEY);
                } catch (IllegalStateException ignored) { /* Concurrent completion. */ }
            }
        }
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "로그인 정보가 만료되었습니다. Google 로그인을 다시 시작해 주세요.");
    }
}

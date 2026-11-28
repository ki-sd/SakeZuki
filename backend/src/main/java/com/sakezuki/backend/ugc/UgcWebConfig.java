package com.sakezuki.backend.ugc;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.concurrent.Semaphore;

@Configuration
class UgcWebConfig implements WebMvcConfigurer {
    private static final String PERMIT = UgcWebConfig.class.getName() + ".UPLOAD_PERMIT";
    // Match ImageAssetService's single processing slot before multipart parsing or quota reservation.
    private final Semaphore uploadRequests = new Semaphore(1);

    private final AdminReviewAccess adminAccess;

    UgcWebConfig(AdminReviewAccess adminAccess) {
        this.adminAccess = adminAccess;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                response.setHeader("Cache-Control", "private, no-store");
                response.setHeader("X-Content-Type-Options", "nosniff");
                if (!(handler instanceof HandlerMethod method)) return true;
                if (method.getBeanType() == AdminReviewController.class) {
                    var auth = SecurityContextHolder.getContext().getAuthentication();
                    if (!(auth instanceof JwtAuthenticationToken jwt) || !jwt.isAuthenticated()) {
                        throw SubmissionErrors.error(401, "로그인이 필요합니다.");
                    }
                    adminAccess.requireCurrent(Long.parseLong(jwt.getToken().getSubject()));
                }
                if (!"POST".equals(request.getMethod())) return true;
                boolean upload = method.getBeanType() == UgcController.class && method.getMethod().getName().equals("upload");
                int max = upload ? SubmissionLimits.MULTIPART_BYTES : SubmissionLimits.JSON_BYTES;
                if (request.getContentLengthLong() > max) throw SubmissionErrors.error(413, "요청 용량을 줄여 주세요.");
                if (!upload) return true;
                SubmissionErrors.uuid(request.getHeader("Idempotency-Key"), 422);
                if (!uploadRequests.tryAcquire()) throw SubmissionErrors.error(503, "이미지 처리 요청이 많습니다.");
                request.setAttribute(PERMIT, Boolean.TRUE);
                return true;
            }

            @Override
            public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
                if (Boolean.TRUE.equals(request.getAttribute(PERMIT))) uploadRequests.release();
            }
        }).addPathPatterns(UgcController.ROOT, UgcController.ROOT + "/**", "/api/user/catalog/**", AdminReviewController.ROOT, AdminReviewController.ROOT + "/**");
    }
}

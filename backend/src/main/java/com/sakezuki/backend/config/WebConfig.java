package com.sakezuki.backend.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        // 로컬 개발에서 Next.js(3000)와 Spring(8080)이 다른 출처일 때 브라우저 요청을 허용한다.
        // 배포의 동일 출처 /api 경로는 Nginx가 Spring으로 전달한다.
        // Next.js 개발 서버 API 요청 허용
        registry.addMapping("/api/**")
                .allowedOrigins("http://localhost:3000")
                .allowedMethods("GET","POST","PUT","PATCH","DELETE","OPTIONS")
                .allowedHeaders("*");
    }
}

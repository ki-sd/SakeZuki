package com.sakezuki.backend.config;

import com.sakezuki.backend.auth.AuthProperties;
import com.sakezuki.backend.security.oauth.CustomOidcUserService;
import com.sakezuki.backend.security.oauth.OAuth2LoginSuccessHandler;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.security.web.context.NullSecurityContextRepository;
import org.springframework.security.web.savedrequest.NullRequestCache;

@Configuration
@EnableWebSecurity
@EnableScheduling
@EnableConfigurationProperties(AuthProperties.class)
public class SecurityConfig {
    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    @Bean
    @Order(1)
    public SecurityFilterChain oauthChain(HttpSecurity http, CustomOidcUserService users,
                                          OAuth2LoginSuccessHandler success, AuthProperties properties) throws Exception {
        http.securityMatcher("/oauth2/**", "/login/oauth2/**")
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                        .sessionFixation(fixation -> fixation.changeSessionId()))
                .requestCache(cache -> cache.requestCache(new NullRequestCache()))
                .oauth2Login(oauth -> oauth.userInfoEndpoint(info -> info.oidcUserService(users))
                        .successHandler(success)
                        .failureHandler((request, response, error) -> {
                            var session = request.getSession(false);
                            if (session != null) session.invalidate();
                            response.sendRedirect(properties.getFrontendUrl() + "/login?error=oauth");
                        }));
        return http.build();
    }

    @Bean
    @Order(2)
    public SecurityFilterChain apiChain(HttpSecurity http, JwtAuthenticationConverter converter) throws Exception {
        http.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .securityContext(context -> context.securityContextRepository(new NullSecurityContextRepository()))
                .requestCache(cache -> cache.requestCache(new NullRequestCache()))
                // Only /api/auth uses ambient session/refresh cookies. Other writes require an explicit Bearer token.
                .csrf(csrf -> csrf.csrfTokenRepository(new HttpSessionCsrfTokenRepository())
                        .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
                        .requireCsrfProtectionMatcher(new org.springframework.security.web.util.matcher.AndRequestMatcher(
                                org.springframework.security.web.csrf.CsrfFilter.DEFAULT_CSRF_MATCHER,
                                org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher.withDefaults().matcher("/api/auth/**"))))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/auth/**", "/api/sake/**", "/api/recommend/**", "/error").permitAll()
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        .requestMatchers("/api/user/**").hasAnyRole("USER", "ADMIN")
                        .anyRequest().denyAll())
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, error) -> jsonError(response, 401, "로그인이 필요합니다."))
                        .accessDeniedHandler((request, response, error) -> jsonError(response, 403, "접근 권한이 없거나 보안 토큰이 만료되었습니다.")))
                .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> jwt.jwtAuthenticationConverter(converter))
                        .authenticationEntryPoint((request, response, error) -> jsonError(response, 401, "로그인이 만료되었습니다."))
                        .accessDeniedHandler((request, response, error) -> jsonError(response, 403, "접근 권한이 없습니다.")))
                .logout(logout -> logout.disable());
        return http.build();
    }

    private static void jsonError(jakarta.servlet.http.HttpServletResponse response, int status, String message)
            throws java.io.IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.setHeader("Cache-Control", "no-store");
        response.getWriter().write("{\"message\":\"" + message + "\"}");
    }

    @Bean
    public PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }
}

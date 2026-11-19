package com.sakezuki.backend.security.jwt;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.util.Base64;

@Configuration
@RequiredArgsConstructor
public class JwtConfig {
    private final JwtProperties jwtProperties;

    @Bean
    public SecretKey secretKey(){
        byte[] secret= Base64.getDecoder().decode(jwtProperties.getSecret());
        if (secret.length < 32) throw new IllegalArgumentException("JWT_SECRET must be at least 32 random bytes, Base64 encoded");
        return new SecretKeySpec(secret, "HmacSHA256");
    }

    @Bean
    public JwtEncoder jwtEncoder(SecretKey secretKey){
        return NimbusJwtEncoder.withSecretKey(secretKey).build();
    }

    @Bean
    public JwtDecoder jwtDecoder(SecretKey secretKey){
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(secretKey).macAlgorithm(MacAlgorithm.HS256).build();
        var defaults = org.springframework.security.oauth2.jwt.JwtValidators.createDefaultWithIssuer(jwtProperties.getIssuer());
        org.springframework.security.oauth2.core.OAuth2TokenValidator<org.springframework.security.oauth2.jwt.Jwt> claims = jwt -> {
            Object roles = jwt.getClaims().get("roles");
            boolean valid = jwt.getSubject() != null && jwt.getSubject().matches("[1-9][0-9]{0,17}")
                    && jwt.getExpiresAt() != null && jwt.getIssuedAt() != null
                    && roles instanceof java.util.List<?> list && !list.isEmpty()
                    && list.stream().allMatch(role -> "ROLE_USER".equals(role) || "ROLE_ADMIN".equals(role));
            // 기본 시간 검증만으로는 exp가 빠진 토큰을 거절하지 않으므로 서비스 계약도 함께 검사한다.
            return valid ? org.springframework.security.oauth2.core.OAuth2TokenValidatorResult.success()
                    : org.springframework.security.oauth2.core.OAuth2TokenValidatorResult.failure(
                    new org.springframework.security.oauth2.core.OAuth2Error("invalid_token", "Invalid access token claims", null));
        };
        decoder.setJwtValidator(new org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator<>(defaults, claims));
        return decoder;
    }

    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter(){
        JwtGrantedAuthoritiesConverter authoritiesConverter=new JwtGrantedAuthoritiesConverter();

        authoritiesConverter.setAuthoritiesClaimName("roles");
        authoritiesConverter.setAuthorityPrefix("");

        JwtAuthenticationConverter authenticationConverter=new JwtAuthenticationConverter();
        authenticationConverter.setJwtGrantedAuthoritiesConverter(authoritiesConverter);
        return authenticationConverter;
    }
}

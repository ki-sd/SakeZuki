package com.sakezuki.backend.security.jwt;

import com.sakezuki.backend.security.CustomUserDetails;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Component;
import java.time.Instant;

@Component
@RequiredArgsConstructor
public class JwtTokenProvider {
    private final JwtProperties jwtProperties;
    private final JwtEncoder jwtEncoder;

    public String createAccessToken(CustomUserDetails userDetails){
        Instant now=Instant.now();
        JwtClaimsSet claimSet=JwtClaimsSet.builder()
                .issuer(jwtProperties.getIssuer())
                .subject(userDetails.getMemberNo().toString())
                .issuedAt(now)
                .expiresAt(now.plus(jwtProperties.getAccessTokenExpiration()))
                .claim("email",userDetails.getUsername())
                .claim("roles",userDetails.getAuthorities().stream()
                                                                .map(GrantedAuthority::getAuthority)
                                                                .toList())
                .build();
        return jwtEncoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claimSet)).getTokenValue();
    }
}

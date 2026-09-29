package com.sakezuki.backend.auth.service;

import com.sakezuki.backend.auth.dto.LoginRequestDTO;
import com.sakezuki.backend.auth.dto.LoginResponseDTO;
import com.sakezuki.backend.security.CustomUserDetails;
import com.sakezuki.backend.security.jwt.JwtProperties;
import com.sakezuki.backend.security.jwt.JwtTokenProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {
    private final AuthenticationManager authenticationManager;
    private final JwtTokenProvider jwtTokenProvider;
    private final JwtProperties jwtProperties;

    @Override
    public LoginResponseDTO login(LoginRequestDTO dto) {
        Authentication authentication= UsernamePasswordAuthenticationToken.unauthenticated(
                dto.getEmail(),dto.getPassword()
        );
        Authentication auth=authenticationManager.authenticate(authentication);
        CustomUserDetails userDetails=(CustomUserDetails) auth.getPrincipal();

        String accessToken=jwtTokenProvider.createAccessToken(userDetails);
        Long expiresIn=jwtProperties.getAccessTokenExpiration().getSeconds();
        return new LoginResponseDTO(accessToken,expiresIn);
    }
}

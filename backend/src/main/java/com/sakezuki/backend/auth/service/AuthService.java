package com.sakezuki.backend.auth.service;

import com.sakezuki.backend.auth.dto.LoginRequestDTO;
import com.sakezuki.backend.auth.dto.LoginResponseDTO;
import com.sakezuki.backend.auth.dto.SocialSignupRequestDTO;
import com.sakezuki.backend.security.oauth.SocialSignupDTO;

public interface AuthService {
    public LoginResponseDTO login(LoginRequestDTO dto);
    public LoginResponseDTO socialSignup(SocialSignupDTO signupInfo, SocialSignupRequestDTO dto);
    public LoginResponseDTO socialLogin(Long memberNo);
}

package com.sakezuki.backend.auth.service;

import com.sakezuki.backend.auth.dto.LoginRequestDTO;
import com.sakezuki.backend.auth.dto.LoginResponseDTO;

public interface AuthService {
    public LoginResponseDTO login(LoginRequestDTO dto);
}

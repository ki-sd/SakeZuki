package com.sakezuki.backend.auth.controller;

import com.sakezuki.backend.auth.dto.LoginRequestDTO;
import com.sakezuki.backend.auth.dto.LoginResponseDTO;
import com.sakezuki.backend.auth.service.AuthService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService aService;

    @PostMapping("/login")
    public ResponseEntity<LoginResponseDTO> login(@RequestBody LoginRequestDTO dto){
        LoginResponseDTO response=aService.login(dto);
        return ResponseEntity.ok(response);
    }
}

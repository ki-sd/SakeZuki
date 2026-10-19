package com.sakezuki.backend.auth.service;

import org.springframework.security.authentication.BadCredentialsException;

public class InvalidRefreshTokenException extends BadCredentialsException {
    public InvalidRefreshTokenException() { super("다시 로그인해 주세요."); }
}

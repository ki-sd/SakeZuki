package com.sakezuki.backend.auth.controller;

import com.sakezuki.backend.auth.AuthProperties;
import com.sakezuki.backend.auth.dto.*;
import com.sakezuki.backend.auth.service.AuthService;
import com.sakezuki.backend.security.oauth.SocialLoginState;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.time.Duration;
import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService aService;
    private final AuthProperties properties;
    private static final String REFRESH_COOKIE = "sakezuki_refresh";

    @GetMapping("/csrf")
    public Map<String, String> csrf(CsrfToken token, HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        return Map.of("token", token.getToken(), "headerName", token.getHeaderName());
    }

    @PostMapping("/login")
    public ResponseEntity<LoginResponseDTO> login(@RequestBody LoginRequestDTO dto, HttpServletRequest request) {
        var result = aService.login(dto);
        revokePrevious(request);
        return complete(result, request);
    }

    @GetMapping("/social/signup-info")
    public ResponseEntity<SocialSignupResponseDTO> signupInfo(HttpServletRequest request) {
        var info = SocialLoginState.read(request, false).signup();
        if (info == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "가입 정보가 없습니다.");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new SocialSignupResponseDTO(info.getProvider(), info.getEmail(), info.getName()));
    }

    @PostMapping("/social/signup")
    public ResponseEntity<LoginResponseDTO> signup(@RequestBody SocialSignupRequestDTO dto, HttpServletRequest request) {
        var info = SocialLoginState.read(request, true).signup();
        if (info == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "가입 정보가 없습니다.");
        var result = aService.socialSignup(info, dto);
        revokePrevious(request);
        return complete(result, request);
    }

    @PostMapping("/social/token")
    public ResponseEntity<LoginResponseDTO> socialToken(HttpServletRequest request) {
        Long member = SocialLoginState.read(request, true).memberNo();
        if (member == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "로그인 정보가 없습니다.");
        var result = aService.socialLogin(member);
        revokePrevious(request);
        return complete(result, request);
    }

    @PostMapping("/refresh")
    public ResponseEntity<LoginResponseDTO> refresh(@CookieValue(name = REFRESH_COOKIE, required = false) String token,
                                                   HttpServletRequest request, HttpServletResponse response) {
        try { return complete(aService.refresh(token), request); }
        catch (org.springframework.security.core.AuthenticationException e) {
            response.addHeader(HttpHeaders.SET_COOKIE, cookie("", Duration.ZERO).toString());
            throw e;
        }
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@CookieValue(name = REFRESH_COOKIE, required = false) String token,
                                       HttpServletRequest request) {
        aService.logout(token);
        invalidate(request);
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, cookie("", Duration.ZERO).toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store").build();
    }

    private void revokePrevious(HttpServletRequest request) {
        if (request.getCookies() == null) return;
        for (var cookie : request.getCookies()) {
            if (REFRESH_COOKIE.equals(cookie.getName())) {
                try { aService.logout(cookie.getValue()); }
                catch (com.sakezuki.backend.auth.service.InvalidRefreshTokenException ignored) { }
            }
        }
    }

    private ResponseEntity<LoginResponseDTO> complete(LoginResponseDTO result, HttpServletRequest request) {
        invalidate(request);
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(HttpHeaders.SET_COOKIE, cookie(result.getRefreshToken(), properties.getRefreshExpiration()).toString())
                .body(result);
    }

    private ResponseCookie cookie(String value, Duration duration) {
        return ResponseCookie.from(REFRESH_COOKIE, value).httpOnly(true).secure(properties.isSecureCookies())
                .sameSite("Lax").path("/api/auth").maxAge(duration).build();
    }

    private void invalidate(HttpServletRequest request) {
        var session = request.getSession(false);
        if (session != null) session.invalidate();
    }
}

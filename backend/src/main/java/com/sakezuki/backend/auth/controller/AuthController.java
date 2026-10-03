package com.sakezuki.backend.auth.controller;

import com.sakezuki.backend.auth.dto.LoginRequestDTO;
import com.sakezuki.backend.auth.dto.LoginResponseDTO;
import com.sakezuki.backend.auth.dto.SocialSignupRequestDTO;
import com.sakezuki.backend.auth.dto.SocialSignupResponseDTO;
import com.sakezuki.backend.auth.service.AuthService;
import com.sakezuki.backend.security.oauth.SocialSignupDTO;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.http.RequestEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService aService;

    private SocialSignupDTO getSocialSignupFromSession(HttpServletRequest request){
        HttpSession session=request.getSession(false);

        if(session==null){
            throw new IllegalArgumentException("소셜 회원 정보가 없습니다.");
        }

        SocialSignupDTO signupInfo=(SocialSignupDTO) session.getAttribute("socialSignup");

        if(signupInfo==null){
            throw new IllegalArgumentException("소셜 회원 정보가 없습니다.");
        }
        return signupInfo;
    }

    @PostMapping("/login")
    public ResponseEntity<LoginResponseDTO> login(@RequestBody LoginRequestDTO dto){
        LoginResponseDTO response=aService.login(dto);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/social/signup-info")
    public ResponseEntity<SocialSignupDTO> getSocialSignupInfo(HttpServletRequest request){
        SocialSignupDTO signupInfo=getSocialSignupFromSession(request);

        SocialSignupResponseDTO response=new SocialSignupResponseDTO(
                signupInfo.getProvider(),
                signupInfo.getEmail(),
                signupInfo.getName()
        );

        return ResponseEntity.ok(signupInfo);
    }

    @PostMapping("/social/signup")
    public ResponseEntity<LoginResponseDTO> socialSignup(@RequestBody SocialSignupRequestDTO dto,HttpServletRequest request){
        SocialSignupDTO signupInfo=getSocialSignupFromSession(request);

        LoginResponseDTO response=aService.socialSignup(signupInfo,dto);
        request.getSession(false).removeAttribute("socialSignup");

        return ResponseEntity.ok(response);
    }

    @PostMapping("/social/token")
    public ResponseEntity<LoginResponseDTO> socialToken(HttpServletRequest request){
        HttpSession session=request.getSession();
        if(session==null){
            throw new IllegalArgumentException("소셜 회원 정보가 없습니다.");
        }

        Long memberNo=(Long)session.getAttribute("socialLoginMemberNo");

        if(memberNo==null){
            throw new IllegalArgumentException("소셜 회원 정보가 없습니다.");
        }

        LoginResponseDTO response=aService.socialLogin(memberNo);
        session.removeAttribute("socialLoginMemberNo");

        return ResponseEntity.ok(response);
    }
}

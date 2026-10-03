package com.sakezuki.backend.member.controller;

import com.sakezuki.backend.member.dto.MemberMeResponseDTO;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/user")
public class MemberController {

    @GetMapping("/me")
    public MemberMeResponseDTO me(@AuthenticationPrincipal Jwt jwt){
        Long memberNo=Long.valueOf(jwt.getSubject());
        String email=jwt.getClaimAsString("email");
        List<String> roles=jwt.getClaimAsStringList("roles");

        return new MemberMeResponseDTO(memberNo,email,roles);
    }
}

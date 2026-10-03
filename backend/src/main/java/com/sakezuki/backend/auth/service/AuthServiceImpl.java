package com.sakezuki.backend.auth.service;

import com.sakezuki.backend.auth.dto.LoginRequestDTO;
import com.sakezuki.backend.auth.dto.LoginResponseDTO;
import com.sakezuki.backend.auth.dto.SocialSignupRequestDTO;
import com.sakezuki.backend.member.service.MemberService;
import com.sakezuki.backend.member.vo.MemberVO;
import com.sakezuki.backend.member.vo.SocialAccountVO;
import com.sakezuki.backend.security.CustomUserDetails;
import com.sakezuki.backend.security.CustomUserDetailsService;
import com.sakezuki.backend.security.jwt.JwtProperties;
import com.sakezuki.backend.security.jwt.JwtTokenProvider;
import com.sakezuki.backend.security.oauth.SocialSignupDTO;
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
    private final MemberService mService;
    private final CustomUserDetailsService userDetailsService;

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

    @Override
    public LoginResponseDTO socialSignup(SocialSignupDTO signupInfo, SocialSignupRequestDTO dto) {
        MemberVO existingMember=mService.getLoginData(signupInfo.getEmail());
        if(existingMember!=null){
            throw new IllegalArgumentException("이미 가입된 이메일입니다.");
        }
        MemberVO member=new MemberVO();
        member.setEmail(signupInfo.getEmail());
        member.setName(signupInfo.getName());
        member.setAddress1(dto.getAddress1());
        member.setAddress2(dto.getAddress2());
        member.setPhone(dto.getPhone());

        SocialAccountVO newSocialAccount=new SocialAccountVO();
        newSocialAccount.setProvider(signupInfo.getProvider());
        newSocialAccount.setProviderUserId(signupInfo.getProviderUserId());

        mService.createSocialMember(member,newSocialAccount);

        CustomUserDetails userDetails=(CustomUserDetails) userDetailsService.loadUserByUsername(member.getEmail());
        String accessToken=jwtTokenProvider.createAccessToken(userDetails);
        Long expiresIn=jwtProperties.getAccessTokenExpiration().getSeconds();

        return new LoginResponseDTO(accessToken,expiresIn);
    }

    @Override
    public LoginResponseDTO socialLogin(Long memberNo) {
        CustomUserDetails userDetails=userDetailsService.loadUserByMemberNo(memberNo);

        String accessToken=jwtTokenProvider.createAccessToken(userDetails);
        Long expiresIn=jwtProperties.getAccessTokenExpiration().getSeconds();

        return new LoginResponseDTO(accessToken,expiresIn);
    }
}

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
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {
    private final AuthenticationManager authenticationManager;
    private final JwtTokenProvider jwtTokenProvider;
    private final JwtProperties jwtProperties;
    private final MemberService mService;
    private final CustomUserDetailsService userDetailsService;
    private final RefreshTokenService refreshTokens;

    @Override
    public LoginResponseDTO login(LoginRequestDTO dto) {
        if (dto.getEmail() == null || dto.getEmail().isBlank() || dto.getEmail().length() > 255
                || dto.getPassword() == null || dto.getPassword().isBlank() || dto.getPassword().length() > 200)
            throw new IllegalArgumentException("이메일과 비밀번호를 확인해 주세요.");
        Authentication authentication= UsernamePasswordAuthenticationToken.unauthenticated(
                dto.getEmail(),dto.getPassword()
        );
        Authentication auth=authenticationManager.authenticate(authentication);
        CustomUserDetails userDetails=(CustomUserDetails) auth.getPrincipal();

        return issue(userDetails);
    }

    @Override
    @Transactional
    public LoginResponseDTO socialSignup(SocialSignupDTO signupInfo, SocialSignupRequestDTO dto) {
        String phone = dto.getPhone() == null ? "" : dto.getPhone().replaceAll("[() -]", "");
        if (!"GOOGLE".equals(signupInfo.getProvider()) || signupInfo.getProviderUserId() == null
                || signupInfo.getEmail() == null || signupInfo.getEmail().length() > 255
                || signupInfo.getName() == null || signupInfo.getName().isBlank() || signupInfo.getName().length() > 50)
            throw new IllegalArgumentException("Google 회원 정보를 확인할 수 없습니다.");
        if (dto.getAddress1() == null || dto.getAddress1().isBlank() || dto.getAddress1().length() > 1000
                || (dto.getAddress2() != null && dto.getAddress2().length() > 1000)
                || dto.getPhone() == null || dto.getPhone().length() > 20 || !phone.matches("\\+?[0-9]{7,15}"))
            throw new IllegalArgumentException("주소와 전화번호를 확인해 주세요.");
        if (mService.findSocialAccount(signupInfo.getProvider(), signupInfo.getProviderUserId()) != null)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 가입된 소셜 계정입니다. 다시 로그인해 주세요.");
        MemberVO existingMember=mService.getLoginData(signupInfo.getEmail());
        if(existingMember!=null){
            throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 사용 중인 이메일입니다. 기존 계정으로 로그인해 주세요.");
        }
        MemberVO member=new MemberVO();
        member.setEmail(signupInfo.getEmail());
        member.setName(signupInfo.getName());
        member.setAddress1(dto.getAddress1().trim());
        member.setAddress2(dto.getAddress2() == null ? null : dto.getAddress2().trim());
        member.setPhone(phone);

        SocialAccountVO newSocialAccount=new SocialAccountVO();
        newSocialAccount.setProvider(signupInfo.getProvider());
        newSocialAccount.setProviderUserId(signupInfo.getProviderUserId());

        mService.createSocialMember(member,newSocialAccount);

        CustomUserDetails userDetails=(CustomUserDetails) userDetailsService.loadUserByUsername(member.getEmail());
        return issue(userDetails);
    }

    @Override
    public LoginResponseDTO socialLogin(Long memberNo) {
        CustomUserDetails userDetails=userDetailsService.loadUserByMemberNo(memberNo);

        return issue(userDetails);
    }

    private LoginResponseDTO issue(CustomUserDetails user) {
        return response(user, refreshTokens.issue(user.getMemberNo()).rawToken());
    }

    private LoginResponseDTO response(CustomUserDetails user, String refresh) {
        LoginResponseDTO result = new LoginResponseDTO(jwtTokenProvider.createAccessToken(user),
                jwtProperties.getAccessTokenExpiration().getSeconds());
        result.setRefreshToken(refresh);
        return result;
    }

    @Override
    public LoginResponseDTO refresh(String raw) {
        var issued = refreshTokens.rotate(raw);
        return response(userDetailsService.loadUserByMemberNo(issued.memberNo()), issued.rawToken());
    }

    @Override
    public void logout(String raw) { refreshTokens.revoke(raw); }
}

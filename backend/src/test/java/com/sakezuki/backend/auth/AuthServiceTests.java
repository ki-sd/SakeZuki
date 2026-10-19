package com.sakezuki.backend.auth;

import com.sakezuki.backend.auth.dto.*;
import com.sakezuki.backend.auth.service.*;
import com.sakezuki.backend.member.service.MemberService;
import com.sakezuki.backend.member.vo.*;
import com.sakezuki.backend.security.*;
import com.sakezuki.backend.security.jwt.*;
import com.sakezuki.backend.security.oauth.SocialSignupDTO;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.*;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.server.ResponseStatusException;
import java.time.Duration;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuthServiceTests {
    private final MemberService members = mock(MemberService.class);
    private final CustomUserDetailsService users = new CustomUserDetailsService(members);
    private final JwtTokenProvider jwt = mock(JwtTokenProvider.class);
    private final RefreshTokenService refresh = mock(RefreshTokenService.class);
    private final BCryptPasswordEncoder passwords = new BCryptPasswordEncoder(4);
    private AuthServiceImpl service() {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(users);
        provider.setPasswordEncoder(passwords);
        JwtProperties properties = new JwtProperties();
        properties.setAccessTokenExpiration(Duration.ofMinutes(15));
        return new AuthServiceImpl(new ProviderManager(provider), jwt, properties, members, users, refresh);
    }
    private MemberVO member(String password) {
        MemberVO member = new MemberVO();
        member.setNo(1L); member.setEmail("member@example.com"); member.setStatus("ACTIVE"); member.setPassword(password);
        MemberAuthorityVO authority = new MemberAuthorityVO(); authority.setAuthority("ROLE_USER");
        member.setAuthorities(List.of(authority));
        return member;
    }
    private LoginRequestDTO credentials(String password) {
        var dto = new LoginRequestDTO(); dto.setEmail("member@example.com"); dto.setPassword(password); return dto;
    }

    @Test void normalLoginUsesBcryptAndIssuesTokens() {
        when(members.getLoginData("member@example.com")).thenReturn(member(passwords.encode("correct")));
        when(jwt.createAccessToken(any())).thenReturn("jwt");
        when(refresh.issue(1L)).thenReturn(new RefreshTokenService.Issued(1L, "opaque"));
        var result = service().login(credentials("correct"));
        assertEquals("jwt", result.getAccessToken()); assertEquals("opaque", result.getRefreshToken());
        assertEquals(900L, result.getExpiresIn());
    }

    @Test void wrongPasswordAndUnknownMemberAreUnauthorized() {
        when(members.getLoginData("member@example.com")).thenReturn(member(passwords.encode("correct")));
        assertThrows(BadCredentialsException.class, () -> service().login(credentials("wrong")));
        when(members.getLoginData("member@example.com")).thenReturn(null);
        assertThrows(BadCredentialsException.class, () -> service().login(credentials("wrong")));
        verifyNoInteractions(refresh);
    }

    @Test void socialMemberWithNullPasswordCannotUsePasswordLogin() {
        when(members.getLoginData("member@example.com")).thenReturn(member(null));
        assertThrows(BadCredentialsException.class, () -> service().login(credentials("wrong")));
    }

    @Test void inactiveMemberCannotLoginOrRefresh() {
        var member = member(passwords.encode("correct")); member.setStatus("BLOCKED");
        when(members.getLoginData("member@example.com")).thenReturn(member);
        when(members.getLoginData(1L)).thenReturn(member);
        assertThrows(org.springframework.security.core.AuthenticationException.class, () -> service().login(credentials("correct")));
        assertThrows(DisabledException.class, () -> service().socialLogin(1L));
    }

    @Test void socialSignupCreatesMemberAndDefaultAuthorityThroughExistingService() {
        var saved = member(null);
        when(members.createSocialMember(any(), any())).thenReturn(1L);
        when(members.getLoginData("member@example.com")).thenReturn(null, saved);
        when(refresh.issue(1L)).thenReturn(new RefreshTokenService.Issued(1L, "opaque"));
        var dto = new SocialSignupRequestDTO(); dto.setAddress1("서울시"); dto.setPhone("010-1234-5678");
        assertNotNull(service().socialSignup(new SocialSignupDTO("GOOGLE", "sub", "member@example.com", "이름"), dto));
        verify(members).createSocialMember(argThat(m -> m.getPassword() == null && "01012345678".equals(m.getPhone())),
                argThat(s -> "GOOGLE".equals(s.getProvider()) && "sub".equals(s.getProviderUserId())));
    }

    @Test void duplicateEmailOrSocialIdentityDoesNotLinkAccounts() {
        var info = new SocialSignupDTO("GOOGLE", "sub", "member@example.com", "이름");
        var dto = new SocialSignupRequestDTO(); dto.setAddress1("서울시"); dto.setPhone("01012345678");
        when(members.getLoginData("member@example.com")).thenReturn(member(null));
        assertThrows(ResponseStatusException.class, () -> service().socialSignup(info, dto));
        when(members.findSocialAccount("GOOGLE", "sub")).thenReturn(new SocialAccountVO());
        assertThrows(ResponseStatusException.class, () -> service().socialSignup(info, dto));
        verify(members, never()).createSocialMember(any(), any());
    }

    @Test void validatesSignupInput() {
        assertThrows(IllegalArgumentException.class, () -> service().socialSignup(
                new SocialSignupDTO("GOOGLE", "sub", "member@example.com", "이름"), new SocialSignupRequestDTO()));
        var dto = new SocialSignupRequestDTO(); dto.setAddress1("서울시"); dto.setPhone("-------");
        assertThrows(IllegalArgumentException.class, () -> service().socialSignup(
                new SocialSignupDTO("GOOGLE", "sub", "member@example.com", "이름"), dto));
    }
}

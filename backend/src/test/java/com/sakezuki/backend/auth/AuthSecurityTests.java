package com.sakezuki.backend.auth;

import com.sakezuki.backend.auth.controller.AuthController;
import com.sakezuki.backend.auth.dto.LoginResponseDTO;
import com.sakezuki.backend.auth.service.AuthService;
import com.sakezuki.backend.commons.ControllerException;
import com.sakezuki.backend.config.SecurityConfig;
import com.sakezuki.backend.member.controller.MemberController;
import com.sakezuki.backend.member.service.MemberService;
import com.sakezuki.backend.member.vo.SocialAccountVO;
import com.sakezuki.backend.security.CustomUserDetails;
import com.sakezuki.backend.security.jwt.*;
import com.sakezuki.backend.security.oauth.*;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.mock.web.*;
import org.springframework.security.config.oauth2.client.CommonOAuth2Provider;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.oauth2.client.registration.*;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AuthSecurityTests {
    private AnnotationConfigWebApplicationContext context;
    private MockMvc mvc;
    @BeforeEach void setup() {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.register(Beans.class, SecurityConfig.class, JwtConfig.class, JwtTokenProvider.class,
                AuthController.class, MemberController.class, ControllerException.class, AdminController.class);
        context.refresh();
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(context.getBean("springSecurityFilterChain", jakarta.servlet.Filter.class)).build();
    }
    @AfterEach void close() { context.close(); }

    @Configuration @EnableWebMvc
    static class Beans {
        @Bean JwtProperties jwtProperties() {
            var p = new JwtProperties(); p.setSecret(Base64.getEncoder().encodeToString(new byte[32]));
            p.setAccessTokenExpiration(Duration.ofMinutes(15)); return p;
        }
        @Bean AuthService authService() { return mock(AuthService.class); }
        @Bean MemberService memberService() { return mock(MemberService.class); }
        @Bean CustomOidcUserService oidcUserService() { return mock(CustomOidcUserService.class); }
        @Bean OAuth2LoginSuccessHandler success(MemberService members, AuthProperties p) { return new OAuth2LoginSuccessHandler(members, p); }
        @Bean UserDetailsService users() { return email -> User.withUsername(email).password("unused").roles("USER").build(); }
        @Bean ClientRegistrationRepository clients() {
            return new InMemoryClientRegistrationRepository(CommonOAuth2Provider.GOOGLE.getBuilder("google")
                    .clientId("test-client").clientSecret("test-secret").scope("openid", "profile", "email")
                    .redirectUri("http://localhost:3000/login/oauth2/code/google").build());
        }
    }
    @RestController static class AdminController {
        @GetMapping("/api/admin/ping") String ping() { return "ok"; }
    }
    private String jwt(String role) {
        return context.getBean(JwtTokenProvider.class).createAccessToken(new CustomUserDetails(1L, "user@example.com", null,
                List.of(new SimpleGrantedAuthority(role))));
    }
    private MockHttpSession csrfSession() throws Exception {
        var result = mvc.perform(get("/api/auth/csrf")).andExpect(status().isOk()).andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }
    private String csrf(MockHttpSession session) {
        return ((org.springframework.security.web.csrf.CsrfToken) session.getAttribute(
                "org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository.CSRF_TOKEN")).getToken();
    }

    @Test void protectsMemberAndAdminEndpointsAndRejectsMalformedJwt() throws Exception {
        mvc.perform(get("/api/user/me")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/ping").header("Authorization", "Bearer " + jwt("ROLE_USER"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/ping").header("Authorization", "Bearer " + jwt("ROLE_ADMIN"))).andExpect(status().isOk());
        mvc.perform(get("/api/user/me").header("Authorization", "Bearer " + jwt("ROLE_USER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.memberNo").value(1));
        mvc.perform(get("/api/user/me").header("Authorization", "Bearer broken")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/unclassified")).andExpect(status().isUnauthorized());
    }

    @Test void loginRequiresCsrfAndRefreshIsNeverInJson() throws Exception {
        mvc.perform(post("/api/auth/login").contentType("application/json").content("{\"email\":\"user@example.com\",\"password\":\"pw\"}"))
                .andExpect(status().isForbidden());
        var tokens = new LoginResponseDTO("access", 900L); tokens.setRefreshToken("private-refresh");
        when(context.getBean(AuthService.class).login(any())).thenReturn(tokens);
        var session = csrfSession();
        mvc.perform(post("/api/auth/login").session(session).header("X-CSRF-TOKEN", csrf(session))
                        .contentType("application/json").content("{\"email\":\"user@example.com\",\"password\":\"pw\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.accessToken").value("access"))
                .andExpect(jsonPath("$.refreshToken").doesNotExist())
                .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("HttpOnly"), org.hamcrest.Matchers.containsString("SameSite=Lax"),
                        org.hamcrest.Matchers.containsString("Path=/api/auth"))));
        assertTrue(session.isInvalid());
    }

    @Test void expiredJwtFailsValidation() {
        var claims = org.springframework.security.oauth2.jwt.JwtClaimsSet.builder().issuer("sakezuki")
                .subject("1").issuedAt(Instant.now().minusSeconds(600)).expiresAt(Instant.now().minusSeconds(300)).build();
        String token = context.getBean(org.springframework.security.oauth2.jwt.JwtEncoder.class).encode(
                org.springframework.security.oauth2.jwt.JwtEncoderParameters.from(
                        org.springframework.security.oauth2.jwt.JwsHeader.with(org.springframework.security.oauth2.jose.jws.MacAlgorithm.HS256).build(), claims)).getTokenValue();
        assertThrows(JwtException.class, () -> context.getBean(JwtDecoder.class).decode(token));
    }

    @Test void oauthOrCookieSessionCannotAuthenticateProtectedApi() throws Exception {
        var session = new MockHttpSession();
        var auth = org.springframework.security.authentication.UsernamePasswordAuthenticationToken.authenticated(
                "oauth", "unused", List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        session.setAttribute("SPRING_SECURITY_CONTEXT", new org.springframework.security.core.context.SecurityContextImpl(auth));
        mvc.perform(get("/api/user/me").session(session)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/ping").session(session)).andExpect(status().isUnauthorized());
    }

    @Test void socialTokenExchangeIsOneTimeAndRefreshLogoutUseCookies() throws Exception {
        var service = context.getBean(AuthService.class);
        var tokens = new LoginResponseDTO(jwt("ROLE_USER"), 900L); tokens.setRefreshToken("a".repeat(43));
        when(service.socialLogin(1L)).thenReturn(tokens);
        var session = csrfSession();
        session.setAttribute(SocialLoginState.KEY, new SocialLoginState(null, 1L, Instant.now().plusSeconds(60)));
        mvc.perform(post("/api/auth/social/token").session(session).header("X-CSRF-TOKEN", csrf(session)))
                .andExpect(status().isOk());
        verify(service, times(1)).socialLogin(1L);
        var nextSession = csrfSession();
        mvc.perform(post("/api/auth/social/token").session(nextSession).header("X-CSRF-TOKEN", csrf(nextSession)))
                .andExpect(status().isUnauthorized());
        when(service.refresh("a".repeat(43))).thenReturn(tokens);
        mvc.perform(post("/api/auth/refresh").session(nextSession).header("X-CSRF-TOKEN", csrf(nextSession))
                        .cookie(new jakarta.servlet.http.Cookie("sakezuki_refresh", "a".repeat(43))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.refreshToken").doesNotExist());
        var logoutSession = csrfSession();
        mvc.perform(post("/api/auth/logout").session(logoutSession).header("X-CSRF-TOKEN", csrf(logoutSession))
                        .cookie(new jakarta.servlet.http.Cookie("sakezuki_refresh", "a".repeat(43))))
                .andExpect(status().isNoContent()).andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.containsString("Max-Age=0")));
        verify(service).logout("a".repeat(43));
    }

    @Test void socialStateIsExpiringOneTimeAndDoesNotCreateSessionOnMissingState() throws Exception {
        var request = new MockHttpServletRequest();
        assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> SocialLoginState.read(request, true));
        assertNull(request.getSession(false));
        request.getSession().setAttribute(SocialLoginState.KEY, new SocialLoginState(null, 1L, Instant.now().minusSeconds(1)));
        assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> SocialLoginState.read(request, true));
        request.getSession().setAttribute(SocialLoginState.KEY, new SocialLoginState(null, 1L, Instant.now().plusSeconds(60)));
        assertEquals(1L, SocialLoginState.read(request, true).memberNo());
        assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> SocialLoginState.read(request, true));
        mvc.perform(get("/api/auth/social/signup-info")).andExpect(status().isUnauthorized());
    }

    @Test void googleSuccessBranchesByProviderSubjectAndDiscardsAuthenticatedSession() throws Exception {
        var claims = Map.<String, Object>of("sub", "google-sub", "email", "user@example.com", "name", "이름", "email_verified", true);
        var id = new OidcIdToken("id", Instant.now(), Instant.now().plusSeconds(60), claims);
        var user = new DefaultOidcUser(List.of(new SimpleGrantedAuthority("OIDC_USER")), id);
        var auth = new OAuth2AuthenticationToken(user, user.getAuthorities(), "google");
        var request = new MockHttpServletRequest(); var response = new MockHttpServletResponse();
        var old = (MockHttpSession) request.getSession(); old.setAttribute("SPRING_SECURITY_CONTEXT", "oauth");
        var handler = context.getBean(OAuth2LoginSuccessHandler.class);
        handler.onAuthenticationSuccess(request, response, auth);
        assertEquals("http://localhost:3000/signup/social", response.getRedirectedUrl());
        assertTrue(old.isInvalid());
        assertNull(request.getSession().getAttribute("SPRING_SECURITY_CONTEXT"));
        assertEquals("google-sub", SocialLoginState.read(request, false).signup().getProviderUserId());
        var social = new SocialAccountVO(); social.setMemberNo(7L);
        when(context.getBean(MemberService.class).findSocialAccount("GOOGLE", "google-sub")).thenReturn(social);
        response = new MockHttpServletResponse(); handler.onAuthenticationSuccess(request, response, auth);
        assertEquals("http://localhost:3000/oauth/callback", response.getRedirectedUrl());
        assertEquals(7L, SocialLoginState.read(request, true).memberNo());
    }

    @Test void oauthFailureInvalidatesSessionAndRedirectsWithoutTokens() throws Exception {
        var session = new MockHttpSession();
        mvc.perform(get("/login/oauth2/code/google").session(session).param("error", "access_denied").param("state", "untrusted"))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("http://localhost:3000/login?error=oauth"));
        assertTrue(session.isInvalid());
    }
}

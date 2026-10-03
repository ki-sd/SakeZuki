package com.sakezuki.backend.security.oauth;

import com.sakezuki.backend.member.service.MemberService;
import com.sakezuki.backend.member.vo.SocialAccountVO;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
@RequiredArgsConstructor
public class OAuth2LoginSuccessHandler implements AuthenticationSuccessHandler {
    private final MemberService mService;

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response, Authentication authentication) throws IOException, ServletException {
        OAuth2AuthenticationToken oauthToken=(OAuth2AuthenticationToken) authentication;
        OidcUser oidcUser=(OidcUser) oauthToken.getPrincipal();

        String provider=oauthToken.getAuthorizedClientRegistrationId().toUpperCase();
        String providerUserId=oidcUser.getSubject();

        SocialAccountVO socialAccount=mService.findSocialAccount(provider,providerUserId);
        if(socialAccount==null){
            SocialSignupDTO signupInfo=new SocialSignupDTO(
                    provider,
                    providerUserId,
                    oidcUser.getEmail(),
                    oidcUser.getFullName()
            );
            request.getSession().setAttribute("socialSignup",signupInfo);

            response.sendRedirect("http://localhost:3000/signup/social");
            return;
        }
        request.getSession().setAttribute("socialLoginMemberNo",socialAccount.getMemberNo());
        response.sendRedirect("http://localhost:3000/oauth/callback");
    }
}

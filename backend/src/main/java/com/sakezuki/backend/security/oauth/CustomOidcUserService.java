package com.sakezuki.backend.security.oauth;

import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Service;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;

@Service
@RequiredArgsConstructor
public class CustomOidcUserService implements OAuth2UserService<OidcUserRequest,OidcUser> {
    private final OidcUserService oService=new OidcUserService();

    @Override
    public OidcUser loadUser(OidcUserRequest userRequest){
        OidcUser oidcUser=oService.loadUser(userRequest);

        if (!"google".equals(userRequest.getClientRegistration().getRegistrationId())
                || !Boolean.TRUE.equals(oidcUser.getEmailVerified())
                || oidcUser.getSubject() == null || oidcUser.getEmail() == null)
            throw new OAuth2AuthenticationException("invalid_google_identity");

        return oidcUser;
    }
}

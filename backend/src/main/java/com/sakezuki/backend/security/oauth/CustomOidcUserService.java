package com.sakezuki.backend.security.oauth;

import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CustomOidcUserService implements OAuth2UserService<OidcUserRequest,OidcUser> {
    private final OidcUserService oService=new OidcUserService();

    @Override
    public OidcUser loadUser(OidcUserRequest userRequest){
        OidcUser oidcUser=oService.loadUser(userRequest);

        String provider=userRequest.getClientRegistration().getRegistrationId().toUpperCase();
        String providerUserId=oidcUser.getSubject();
        String email=oidcUser.getEmail();
        String name=oidcUser.getFullName();

        return oidcUser;
    }
}

package com.sakezuki.backend.security.oauth;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class SocialSignupDTO {
    private String provider,providerUserId,email,name;
}

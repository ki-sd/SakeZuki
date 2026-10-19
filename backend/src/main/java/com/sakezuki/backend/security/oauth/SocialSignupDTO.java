package com.sakezuki.backend.security.oauth;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class SocialSignupDTO implements java.io.Serializable {
    private String provider,providerUserId,email,name;
}

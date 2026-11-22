package com.sakezuki.backend.identity;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter @Setter @Component
@ConfigurationProperties("identity")
public class IdentityProperties {
    private String hashKey = "";
    private String termsVersion = "";
    private String privacyVersion = "";
    private String termsUrl = "";
    private String privacyUrl = "";
    private String providerOrigin = "";
}

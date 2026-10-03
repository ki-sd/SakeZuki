package com.sakezuki.backend.auth.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class SocialSignupResponseDTO {
    private String provider,email,name;
}

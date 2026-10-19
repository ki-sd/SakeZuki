package com.sakezuki.backend.auth.dto;

import lombok.Data;
import com.fasterxml.jackson.annotation.JsonIgnore;

@Data
public class LoginResponseDTO {
    private String accessToken;
    private String tokenType="Bearer";
    private Long expiresIn;
    @JsonIgnore
    private String refreshToken;

    public LoginResponseDTO(String accessToken,Long expiresIn){
        this.accessToken=accessToken;
        this.expiresIn=expiresIn;
    }
}

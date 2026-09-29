package com.sakezuki.backend.auth.dto;

import lombok.Data;

@Data
public class LoginResponseDTO {
    private String accessToken;
    private String tokenType="Bearer";
    private Long expiresIn;

    public LoginResponseDTO(String accessToken,Long expiresIn){
        this.accessToken=accessToken;
        this.expiresIn=expiresIn;
    }
}

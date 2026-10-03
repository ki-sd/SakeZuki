package com.sakezuki.backend.member.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

@Data
@AllArgsConstructor
public class MemberMeResponseDTO {
    private Long memberNo;
    private String email;
    private List<String> roles;
}

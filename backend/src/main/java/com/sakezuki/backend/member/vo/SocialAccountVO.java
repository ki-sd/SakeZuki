package com.sakezuki.backend.member.vo;

import lombok.Data;

@Data
public class SocialAccountVO {
    private Long no,memberNo;
    private String provider,providerUserId;
}

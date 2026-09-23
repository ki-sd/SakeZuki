package com.sakezuki.backend.member.vo;

import lombok.Data;

@Data
public class MemberAuthorityVO {
    private Long no,memberNo;
    private String authority;
}

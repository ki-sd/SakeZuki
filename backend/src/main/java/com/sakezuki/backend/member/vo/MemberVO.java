package com.sakezuki.backend.member.vo;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
public class MemberVO {
    private Long no;
    private String email,password,name,address1,address2,phone,status;
    private LocalDateTime createdAt,updatedAt;
    // 등급
    private MemberGradeVO grade;
    // 권한
    private List<MemberAuthorityVO> authorities=new ArrayList<>();
}

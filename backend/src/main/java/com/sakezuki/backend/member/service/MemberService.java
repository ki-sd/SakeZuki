package com.sakezuki.backend.member.service;

import com.sakezuki.backend.member.vo.MemberVO;

public interface MemberService {
    public MemberVO getLoginData(String email);
}

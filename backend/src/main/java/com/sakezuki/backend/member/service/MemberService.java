package com.sakezuki.backend.member.service;

import com.sakezuki.backend.member.vo.MemberVO;
import com.sakezuki.backend.member.vo.SocialAccountVO;

public interface MemberService {
    public MemberVO getLoginData(String email);
    public SocialAccountVO findSocialAccount(String provider,String providerUserId);
    public Long createSocialMember(MemberVO member,SocialAccountVO socialAccount);
    public MemberVO getLoginData(Long memberNo);
}

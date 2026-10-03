package com.sakezuki.backend.member.mapper;

import com.sakezuki.backend.member.vo.MemberAuthorityVO;
import com.sakezuki.backend.member.vo.MemberVO;
import com.sakezuki.backend.member.vo.SocialAccountVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.springframework.security.core.parameters.P;

import java.util.List;

@Mapper
public interface MemberMapper {
    public MemberVO findByEmail(@Param("email") String email);
    public List<MemberAuthorityVO> getAuthorities(@Param("memberNo") Long memberNo);
    public SocialAccountVO findSocialAccount(@Param("provider") String provider, @Param("providerUserId") String providerUserId);

    public int insertMember(MemberVO member);
    public int insertSocialAccount(SocialAccountVO socialAccount);
    public int insertAuthority(@Param("memberNo") Long memberNo,@Param("authority") String authority);
    public int insertGrade(@Param("memberNo") Long memberNo,@Param("grade") String grade);
    public MemberVO findByNo(Long memberNo);
}

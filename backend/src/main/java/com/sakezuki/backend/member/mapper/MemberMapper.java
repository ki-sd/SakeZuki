package com.sakezuki.backend.member.mapper;

import com.sakezuki.backend.member.vo.MemberAuthorityVO;
import com.sakezuki.backend.member.vo.MemberVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface MemberMapper {
    public MemberVO findByEmail(@Param("email") String email);
    public List<MemberAuthorityVO> getAuthorities(@Param("memberNo") Long memberNo);
}

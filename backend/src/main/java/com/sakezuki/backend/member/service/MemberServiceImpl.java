package com.sakezuki.backend.member.service;

import com.sakezuki.backend.member.mapper.MemberMapper;
import com.sakezuki.backend.member.vo.MemberAuthorityVO;
import com.sakezuki.backend.member.vo.MemberVO;
import com.sakezuki.backend.member.vo.SocialAccountVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class MemberServiceImpl implements MemberService{
    private final MemberMapper mMapper;

    @Override
    public MemberVO getLoginData(String email) {
        MemberVO vo=mMapper.findByEmail(email);
        if(vo!=null) {
            List<MemberAuthorityVO> authorities = mMapper.getAuthorities(vo.getNo());
            vo.setAuthorities(authorities);
        }
        return vo;
    }

    @Override
    public MemberVO getLoginData(Long memberNo) {
        MemberVO vo=mMapper.findByNo(memberNo);

        if(vo!=null){
            List<MemberAuthorityVO> authorities=mMapper.getAuthorities(vo.getNo());
            vo.setAuthorities(authorities);
        }
        return vo;
    }

    @Override
    public SocialAccountVO findSocialAccount(String provider, String providerUserId) {
        return mMapper.findSocialAccount(provider,providerUserId);
    }

    @Override
    @Transactional
    public Long createSocialMember(MemberVO member, SocialAccountVO socialAccount) {
        mMapper.insertMember(member);

        Long memberNo=member.getNo();

        socialAccount.setMemberNo(memberNo);
        mMapper.insertSocialAccount(socialAccount);
        mMapper.insertAuthority(memberNo,"ROLE_USER");
        mMapper.insertGrade(memberNo,"일반회원");
        return memberNo;
    }

}

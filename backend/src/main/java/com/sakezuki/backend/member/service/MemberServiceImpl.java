package com.sakezuki.backend.member.service;

import com.sakezuki.backend.member.mapper.MemberMapper;
import com.sakezuki.backend.member.vo.MemberAuthorityVO;
import com.sakezuki.backend.member.vo.MemberVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

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
}

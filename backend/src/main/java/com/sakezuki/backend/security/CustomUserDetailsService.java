package com.sakezuki.backend.security;

import com.sakezuki.backend.member.service.MemberService;
import com.sakezuki.backend.member.vo.MemberAuthorityVO;
import com.sakezuki.backend.member.vo.MemberVO;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class CustomUserDetailsService implements UserDetailsService{
    private final MemberService mService;

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        MemberVO member=mService.getLoginData(username);
        if(member==null){
            throw new UsernameNotFoundException("아이디나 비밀번호가 잘못되었습니다.");
        }
        List<GrantedAuthority> authorities=new ArrayList<>();
        for(MemberAuthorityVO vo:member.getAuthorities()){
            authorities.add(new SimpleGrantedAuthority(vo.getAuthority()));
        }
        return new CustomUserDetails(member.getNo(),member.getEmail(),member.getPassword(),authorities);
    }
}

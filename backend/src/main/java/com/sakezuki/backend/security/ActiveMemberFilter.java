package com.sakezuki.backend.security;

import com.sakezuki.backend.member.service.MemberService;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;

@RequiredArgsConstructor
public class ActiveMemberFilter extends OncePerRequestFilter {
    private final MemberService members;
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken jwt) {
            // 서명된 역할은 발급 시점의 정보다. 비활성화는 Access Token 만료까지 기다리지 않고 차단한다.
            var member = members.getLoginData(Long.valueOf(jwt.getToken().getSubject()));
            if (member == null || !"ACTIVE".equals(member.getStatus())) {
                response.setStatus(401);
                response.setContentType("application/json;charset=UTF-8");
                response.setHeader("Cache-Control", "no-store");
                response.getWriter().write("{\"message\":\"사용할 수 없는 계정입니다.\"}");
                return;
            }
        }
        chain.doFilter(request, response);
    }
}

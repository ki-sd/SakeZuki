package com.sakezuki.backend.identity;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import java.net.URI;
import java.time.Instant;
import java.util.UUID;

@Service @RequiredArgsConstructor
public class IdentityService {
    private final IdentityProvider provider;
    private final IdentityProperties properties;
    private final IdentityMapper mapper;
    private final IdentityTransactions transactions;
    public record Started(String requestId, String redirectUrl) {}
    public boolean available() {
        return provider.available() && !properties.getHashKey().isBlank() && !properties.getTermsVersion().isBlank()
                && !properties.getPrivacyVersion().isBlank() && !properties.getTermsUrl().isBlank()
                && !properties.getPrivacyUrl().isBlank() && !properties.getProviderOrigin().isBlank();
    }
    public Started start(Long member, String terms, String privacy) {
        if (!available()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "본인확인 서비스 연결을 준비 중입니다.");
        transactions.consent(member, terms, privacy);
        String id = UUID.randomUUID().toString();
        var challenge = provider.start(id);
        URI target = URI.create(challenge.redirectUrl());
        URI origin = URI.create(properties.getProviderOrigin());
        if (!"https".equals(origin.getScheme()) || origin.getHost() == null || target.getHost() == null
                || !"https".equals(target.getScheme()) || target.getUserInfo() != null || target.getFragment() != null
                || !target.getHost().equalsIgnoreCase(origin.getHost()) || port(target) != port(origin))
            throw new IllegalStateException("본인확인 이동 주소 설정이 올바르지 않습니다.");
        mapper.challenge(new IdentityMapper.Challenge(id, member, challenge.reference(), Instant.now().plusSeconds(600).getEpochSecond(), false));
        return new Started(id, challenge.redirectUrl());
    }
    private int port(URI uri) { return uri.getPort() == -1 ? 443 : uri.getPort(); }
    public void complete(Long member, String id, String proof) {
        if (!available()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "본인확인 서비스 연결을 준비 중입니다.");
        var challenge = mapper.readChallenge(member, id);
        if (challenge == null || challenge.consumed() || challenge.expiresAtEpoch() <= Instant.now().getEpochSecond())
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "본인확인을 다시 시작해 주세요.");
        if (proof == null || proof.isBlank() || proof.length() > 4096) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "본인확인 결과를 확인해 주세요.");
        // 브라우저는 사업자 확인용 증표만 전달한다. 성공 여부·성인 여부·식별값은 서버 조회 결과만 사용한다.
        transactions.finish(member, id, provider.verify(challenge.providerReference(), proof));
    }
}

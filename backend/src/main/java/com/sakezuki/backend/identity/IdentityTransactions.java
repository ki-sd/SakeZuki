package com.sakezuki.backend.identity;

import com.sakezuki.backend.credit.CreditMapper;
import com.sakezuki.backend.credit.CreditProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;

@Service @RequiredArgsConstructor
public class IdentityTransactions {
    private final IdentityMapper identities;
    private final CreditMapper credits;
    private final CreditProperties policy;
    private final IdentityProperties properties;

    @Transactional
    public void consent(Long member, String terms, String privacy) {
        active(member);
        if (properties.getTermsVersion().isBlank() || properties.getPrivacyVersion().isBlank()
                || !properties.getTermsVersion().equals(terms) || !properties.getPrivacyVersion().equals(privacy))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "현재 약관과 개인정보 처리방침에 동의해 주세요.");
        identities.consent(member, terms, privacy, Instant.now().getEpochSecond());
    }

    @Transactional
    public void finish(Long member, String id, IdentityProvider.Verified verified) {
        active(member);
        var challenge = identities.lockChallenge(member, id);
        long now = Instant.now().getEpochSecond();
        if (challenge == null || challenge.consumed() || challenge.expiresAtEpoch() <= now
                || verified == null || !challenge.providerReference().equals(verified.reference())
                || verified.verifiedAt() == null || verified.verifiedAt().isBefore(Instant.ofEpochSecond(challenge.expiresAtEpoch() - 600))
                || verified.verifiedAt().isAfter(Instant.now().plusSeconds(30)))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "본인확인 결과가 만료되었거나 올바르지 않습니다.");
        if (!verified.adult()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "성인확인이 필요합니다.");
        var profile = identities.profile(member);
        if (profile == null || !properties.getTermsVersion().equals(profile.termsVersion())
                || !properties.getPrivacyVersion().equals(profile.privacyVersion()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "현재 약관에 다시 동의해 주세요.");
        if (profile.verifiedAtEpoch() != null) { identities.consume(id); return; }
        // 유일 제약으로 동일인이 여러 소셜 계정에서 동시에 가입해도 최초 지급은 한 회원만 성공한다.
        // HMAC도 개인정보에 해당하므로 보유 근거·기간과 키 교체 정책은 계약 전에 확정해야 한다.
        identities.claim(fingerprint(verified.scope(), verified.subject()), member, now);
        identities.verified(member, now);
        if (policy.getInitialGrant() < 0 || policy.getInitialGrant() > 10000) throw new IllegalStateException("최초 지급 정책을 확인해 주세요.");
        credits.createAccount(member, policy.getInitialGrant());
        credits.ledger(member, "INITIAL", "GRANT", policy.getInitialGrant(), policy.getInitialGrant(), now);
        identities.consume(id);
    }

    private void active(Long member) {
        if (!"ACTIVE".equals(credits.lockMember(member))) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "사용할 수 없는 계정입니다.");
    }
    String fingerprint(String scope, String subject) {
        if (scope == null || scope.isBlank() || subject == null || subject.isBlank())
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "중복가입 확인정보가 없습니다.");
        try {
            byte[] key = Base64.getDecoder().decode(properties.getHashKey());
            if (key.length < 32) throw new IllegalStateException("본인확인 해시 키 설정이 필요합니다.");
            Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal((scope.length() + ":" + scope + subject).getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException | IllegalArgumentException error) { throw new IllegalStateException("본인확인 해시 설정을 확인해 주세요."); }
    }
}

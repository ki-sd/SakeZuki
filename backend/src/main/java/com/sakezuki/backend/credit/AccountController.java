package com.sakezuki.backend.credit;

import com.sakezuki.backend.identity.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;
import java.util.Map;

@RestController @RequiredArgsConstructor
@RequestMapping("/api/user")
public class AccountController {
    private final CreditMapper credits;
    private final IdentityMapper identities;
    private final IdentityService verification;
    private final IdentityProperties policy;
    private final CreditProperties creditPolicy;
    public record Consent(String termsVersion, String privacyVersion) {}
    public record Proof(String requestId, String proof) {}

    @GetMapping("/account")
    public ResponseEntity<?> account(@AuthenticationPrincipal Jwt jwt) {
        Long member = Long.valueOf(jwt.getSubject());
        var profile = identities.profile(member);
        Long balance = credits.balance(member);
        return privateResult(Map.of("verified", profile != null && profile.verifiedAtEpoch() != null && profile.adult(),
                "balance", balance == null ? 0 : balance, "verificationAvailable", verification.available(),
                "termsVersion", policy.getTermsVersion(), "privacyVersion", policy.getPrivacyVersion(),
                "termsUrl", policy.getTermsUrl(), "privacyUrl", policy.getPrivacyUrl(),
                "historyDays", creditPolicy.getHistoryRetention().toDays(), "initialGrant", creditPolicy.getInitialGrant()));
    }
    @PostMapping("/verification/start")
    public ResponseEntity<?> start(@AuthenticationPrincipal Jwt jwt, @RequestBody Consent consent) {
        return privateResult(verification.start(Long.valueOf(jwt.getSubject()), consent.termsVersion(), consent.privacyVersion()));
    }
    @PostMapping("/verification/complete")
    public ResponseEntity<?> complete(@AuthenticationPrincipal Jwt jwt, @RequestBody Proof proof) {
        verification.complete(Long.valueOf(jwt.getSubject()), proof.requestId(), proof.proof());
        return account(jwt);
    }
    @GetMapping("/recommendations")
    public ResponseEntity<?> history(@AuthenticationPrincipal Jwt jwt, @RequestParam(defaultValue = "0") int page) {
        return privateResult(credits.history(Long.valueOf(jwt.getSubject()), Instant.now().getEpochSecond(), offset(page, 30)));
    }
    @GetMapping(value = "/recommendations/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> detail(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        var request = credits.byId(Long.valueOf(jwt.getSubject()), id);
        if (request == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "추천 기록을 찾을 수 없습니다.");
        return privateResult(PaidRecommendationService.replay(request));
    }
    @GetMapping("/credits/ledger")
    public ResponseEntity<?> ledger(@AuthenticationPrincipal Jwt jwt, @RequestParam(defaultValue = "0") int page) {
        return privateResult(credits.entries(Long.valueOf(jwt.getSubject()), offset(page, 50)));
    }
    private int offset(int page, int size) {
        if (page < 0 || page > 10000) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "페이지를 확인해 주세요.");
        return page * size;
    }
    private <T> ResponseEntity<T> privateResult(T body) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body); }
}

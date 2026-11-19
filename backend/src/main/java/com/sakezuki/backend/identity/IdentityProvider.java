package com.sakezuki.backend.identity;

import java.time.Instant;

/** 사업자 SDK는 서버 통신·서명·거래번호·서비스 식별 범위를 검증한 뒤 이 계약으로 결과를 반환한다. */
public interface IdentityProvider {
    record Challenge(String reference, String redirectUrl) {}
    record Verified(String reference, String scope, String subject, boolean adult, Instant verifiedAt) {}
    boolean available();
    Challenge start(String requestId);
    Verified verify(String reference, String proof);
}

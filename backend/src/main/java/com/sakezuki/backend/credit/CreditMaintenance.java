package com.sakezuki.backend.credit;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.Instant;

@Component
@RequiredArgsConstructor
@lombok.extern.slf4j.Slf4j
public class CreditMaintenance {
    private final CreditMapper mapper;
    private final CreditTransactions transactions;
    private final CreditProperties properties;

    @Scheduled(fixedDelay = 60000)
    public void recover() {
        long now = Instant.now().getEpochSecond();
        for (var request : mapper.stale(now - properties.getReservationTimeout().getSeconds())) {
            try { transactions.refund(request.memberNo(), request.id(), true); }
            catch (RuntimeException error) { log.warn("크레딧 예약 복구 지연: {}", error.getClass().getSimpleName()); }
        }
        // 사용자 기록만 지운다. 멱등성 메타데이터와 원장은 별도 보존 정책 확정 전 자동 삭제하지 않는다.
        mapper.eraseExpired(now);
        mapper.eraseChallenges(now);
    }
}

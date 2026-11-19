package com.sakezuki.backend.recommend.service;

import com.sakezuki.backend.credit.CreditProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

@Component @RequiredArgsConstructor
public class GenerationGuard {
    private final CreditProperties properties;
    private final ReentrantLock lock = new ReentrantLock();
    private LocalDate date = LocalDate.now(ZoneOffset.UTC);
    private int attempts;

    public <T> T generate(Supplier<T> cached, Supplier<T> generate) {
        if (!lock.tryLock()) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "추천 생성이 혼잡합니다. 잠시 후 다시 시도해 주세요.");
        try {
            // 대기 중 다른 요청이 저장한 캐시는 다시 사용한다. 캐시 조회 자체는 일일 생성 한도를 소비하지 않는다.
            T existing = cached.get();
            if (existing != null) return existing;
            LocalDate today = LocalDate.now(ZoneOffset.UTC);
            if (!date.equals(today)) { date = today; attempts = 0; }
            if (attempts >= properties.getDailyGenerationLimit())
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "오늘의 추천 생성 한도에 도달했습니다. 잠시 후 다시 이용해 주세요.");
            attempts++;
            // 실패도 사업자 비용이 발생할 수 있어 생성 시도 한도에 포함한다. 사용자 예약은 별도로 환불한다.
            return generate.get();
        } finally { lock.unlock(); }
    }
}

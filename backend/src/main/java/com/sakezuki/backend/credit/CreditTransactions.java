package com.sakezuki.backend.credit;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CreditTransactions {
    private final CreditMapper mapper;
    private final CreditProperties properties;
    public record Reservation(CreditMapper.Request request, boolean owner) {}

    @Transactional
    public Reservation reserve(Long member, String key, String hash, String kind, String input) {
        // 회원→잔액→요청 순서로 잠가 같은 회원의 예약과 환불이 서로 충돌하지 않게 한다.
        // 계정 상태도 매번 DB에서 확인해 아직 유효한 JWT로 비활성 계정이 과금되는 것을 막는다.
        if (!"ACTIVE".equals(mapper.lockMember(member))) throw error(HttpStatus.UNAUTHORIZED, "사용할 수 없는 계정입니다.");
        if (!mapper.verified(member)) throw error(HttpStatus.FORBIDDEN, "본인확인 및 성인확인을 완료해 주세요.");
        Long balance = mapper.lockBalance(member);
        var existing = mapper.byKey(member, key);
        if (existing != null) {
            if (!existing.payloadHash().equals(hash)) throw error(HttpStatus.CONFLICT, "동일 요청 키의 입력이 다릅니다.");
            return new Reservation(existing, false);
        }
        if (balance == null || balance < 1) throw error(HttpStatus.PAYMENT_REQUIRED, "크레딧이 부족합니다. 충전 기능은 준비 중입니다.");
        long now = Instant.now().getEpochSecond();
        var request = new CreditMapper.Request(UUID.randomUUID().toString(), member, key, hash, kind, input,
                "RESERVED", null, now, null, null);
        mapper.reserve(request);
        if (mapper.change(member, -1) != 1) throw new IllegalStateException("크레딧 예약에 실패했습니다.");
        mapper.ledger(member, request.id(), "RESERVE", -1, balance - 1, now);
        return new Reservation(request, true);
    }

    @Transactional
    public String complete(Long member, String id, String snapshot) {
        mapper.lockBalance(member);
        var request = mapper.lockRequest(member, id);
        if (request == null || !"RESERVED".equals(request.status())) throw error(HttpStatus.CONFLICT, "처리가 종료된 요청입니다. 기록을 확인해 주세요.");
        long now = Instant.now().getEpochSecond();
        mapper.complete(id, snapshot, now, now + properties.getHistoryRetention().getSeconds());
        mapper.ledger(member, id, "COMMIT", 0, mapper.balance(member), now);
        return snapshot;
    }

    @Transactional
    public void refund(Long member, String id, boolean timeout) {
        Long balance = mapper.lockBalance(member);
        var request = mapper.lockRequest(member, id);
        if (request == null || !"RESERVED".equals(request.status())) return;
        long now = Instant.now().getEpochSecond();
        if (timeout && request.createdAtEpoch() >= now - properties.getReservationTimeout().getSeconds()) return;
        // 상태 전이와 잔액·원장을 같은 트랜잭션에 넣어 재시작과 중복 복구도 한 번만 환불한다.
        mapper.fail(id, timeout ? "TIMED_OUT" : "FAILED");
        if (mapper.change(member, 1) != 1) throw new IllegalStateException("크레딧 복구에 실패했습니다.");
        mapper.ledger(member, id, "REFUND", 1, balance + 1, now);
    }

    static ResponseStatusException error(HttpStatus status, String message) { return new ResponseStatusException(status, message); }
}

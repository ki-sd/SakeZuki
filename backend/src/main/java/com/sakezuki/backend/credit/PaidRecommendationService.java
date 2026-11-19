package com.sakezuki.backend.credit;

import com.sakezuki.backend.recommend.service.RecommendService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PaidRecommendationService {
    private final CreditTransactions transactions;
    private final RecommendService recommendations;
    private final ObjectMapper json;
    private final com.sakezuki.backend.sake.service.SakeService sakes;

    public String recommend(Long member, String key, String kind, String food, Long sakeNo) {
        if (key == null || !key.matches("[0-9a-fA-F-]{36}") || !UUID.fromString(key).toString().equalsIgnoreCase(key))
            throw CreditTransactions.error(HttpStatus.BAD_REQUEST, "올바른 Idempotency-Key가 필요합니다.");
        var condition = com.sakezuki.backend.recommend.RecommendationCondition.of(kind, food, sakeNo);
        String input = condition.input();
        var reservation = transactions.reserve(member, key.toLowerCase(java.util.Locale.ROOT), condition.hash(), kind, input);
        var request = reservation.request();
        if (!reservation.owner()) {
            return replay(request);
        }
        // 외부 AI 호출은 예약 트랜잭션이 끝난 뒤 수행한다. DB 연결·행 잠금을 대기 시간 내내 점유하지 않는다.
        try {
            Object result;
            if ("SAKE".equals(kind)) result = recommendations.sakeRecommendList(input);
            else {
                var recommended = recommendations.foodRecommendList(sakeNo);
                // 음식 추천도 당시 선택한 제품 정보를 함께 저장해 이후 공용 DB 변경과 분리한다.
                result = java.util.Map.of("sakeNo", sakeNo, "sake", sakes.getSakeDetail(sakeNo), "recommends", recommended.getRecommends());
            }
            return transactions.complete(member, request.id(), json.writeValueAsString(result));
        } catch (RuntimeException error) {
            try { transactions.refund(member, request.id(), false); }
            catch (RuntimeException recovery) { error.addSuppressed(recovery); }
            throw error;
        }
    }

    public static String replay(CreditMapper.Request request) {
        if ("RESERVED".equals(request.status())) throw CreditTransactions.error(HttpStatus.CONFLICT, "추천을 처리 중입니다. 같은 요청으로 잠시 후 다시 확인해 주세요.");
        if (!"COMPLETED".equals(request.status())) throw CreditTransactions.error(HttpStatus.CONFLICT, "실패한 요청은 복구되었습니다. 새 요청으로 다시 시도해 주세요.");
        if (request.expiresAtEpoch() == null || request.expiresAtEpoch() <= Instant.now().getEpochSecond() || request.resultSnapshot() == null)
            throw CreditTransactions.error(HttpStatus.GONE, "추천 기록의 열람 기간이 만료되었습니다.");
        return request.resultSnapshot();
    }

}

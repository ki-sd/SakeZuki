package com.sakezuki.backend.recommend;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

public record RecommendationCondition(String kind, String input, String hash) {
    public static RecommendationCondition of(String kind, String food, Long sakeNo) {
        String input;
        if ("SAKE".equals(kind)) {
            if (food == null || food.isBlank() || food.trim().length() > 200)
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "음식명은 1-200자로 입력해 주세요.");
            input = food.trim();
        } else if ("FOOD".equals(kind)) {
            if (sakeNo == null || sakeNo < 1) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "사케를 선택해 주세요.");
            input = sakeNo.toString();
        } else throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "추천 방향을 확인해 주세요.");
        try {
            // PR #2의 trim·해시 규칙을 그대로 공유한다. 띄어쓰기·동의어·대소문자를 임의로 합치면
            // 서로 다른 주문을 무료 기록으로 잘못 안내하거나 기존 멱등성 키가 바뀔 수 있다.
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((kind + "\n" + input).getBytes(StandardCharsets.UTF_8)));
            return new RecommendationCondition(kind, input, hash);
        } catch (java.security.NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
}

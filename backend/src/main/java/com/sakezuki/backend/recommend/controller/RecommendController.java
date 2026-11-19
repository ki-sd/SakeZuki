package com.sakezuki.backend.recommend.controller;

import com.sakezuki.backend.recommend.dto.*;
import com.sakezuki.backend.recommend.service.RecommendService;
import com.sakezuki.backend.credit.PaidRecommendationService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.http.MediaType;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;


@RestController
@RequiredArgsConstructor
@RequestMapping("/api/recommend")
public class RecommendController {
    private final PaidRecommendationService rService;

    // 프론트가 선택한 DB 제품 번호를 받아 사케→음식 추천으로 연결한다.
    @PostMapping(value = "/food", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> foodRecommend(@RequestBody FoodRecommendRequest request, @AuthenticationPrincipal Jwt jwt,
                                                @RequestHeader(value = "Idempotency-Key", required = false) String key){
        // 기존 응답 형태를 유지하되 캐시 적중과 관계없이 개인별 예약·스냅샷 저장을 거친다.
        return ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore())
                .body(rService.recommend(Long.valueOf(jwt.getSubject()), key, "FOOD", null, request.getSakeNo()));
    }

    // 음식명 입력을 받아 음식→사케 추천으로 연결한다. 제품 상세값은 요청 본문에서 받지 않는다.
    @PostMapping(value = "/sake", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> sakeRecommend(@RequestBody SakeRecommendRequest request, @AuthenticationPrincipal Jwt jwt,
                                                @RequestHeader(value = "Idempotency-Key", required = false) String key){
        return ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore())
                .body(rService.recommend(Long.valueOf(jwt.getSubject()), key, "SAKE", request.getFood(), null));
    }


}

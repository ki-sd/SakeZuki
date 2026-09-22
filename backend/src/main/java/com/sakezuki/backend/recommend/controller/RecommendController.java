package com.sakezuki.backend.recommend.controller;

import com.sakezuki.backend.recommend.dto.*;
import com.sakezuki.backend.recommend.service.RecommendService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;


@RestController
@RequiredArgsConstructor
@RequestMapping("/api/recommend")
public class RecommendController {
    private final RecommendService rService;

    // 프론트가 선택한 DB 제품 번호를 받아 사케→음식 추천으로 연결한다.
    @PostMapping("/food")
    public ResponseEntity<FoodRecommendResponse> foodRecommend(@RequestBody FoodRecommendRequest request){
        FoodRecommendResponse response=rService.foodRecommendList(request.getSakeNo());
        return ResponseEntity.ok(response);
    }

    // 음식명 입력을 받아 음식→사케 추천으로 연결한다. 제품 상세값은 요청 본문에서 받지 않는다.
    @PostMapping("/sake")
    public ResponseEntity<SakeRecommendResponse> sakeRecommend(@RequestBody SakeRecommendRequest request){
        SakeRecommendResponse response=rService.sakeRecommendList(request.getFood());
        return ResponseEntity.ok(response);
    }


}

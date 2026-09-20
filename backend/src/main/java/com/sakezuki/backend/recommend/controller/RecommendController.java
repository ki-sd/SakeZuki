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

    @PostMapping("/food")
    public ResponseEntity<FoodRecommendResponse> foodRecommend(@RequestBody FoodRecommendRequest request){
        FoodRecommendResponse response=rService.foodRecommendList(request.getSakeNo());
        return ResponseEntity.ok(response);
    }

    @PostMapping("/sake")
    public ResponseEntity<SakeRecommendResponse> sakeRecommend(@RequestBody SakeRecommendRequest request){
        SakeRecommendResponse response=rService.sakeRecommendList(request.getFood());
        return ResponseEntity.ok(response);
    }


}

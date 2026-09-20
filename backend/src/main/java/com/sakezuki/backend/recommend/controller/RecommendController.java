package com.sakezuki.backend.recommend.controller;

import com.sakezuki.backend.recommend.dto.*;
import com.sakezuki.backend.recommend.mapper.RecommendMapper;
import com.sakezuki.backend.recommend.service.GeminiRecommendService;
import com.sakezuki.backend.recommend.service.RecommendService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/recommend")
public class RecommendController {
    private final RecommendService rService;
//    private final GeminiRecommendService gService;
//    private final RecommendMapper rMapper;

    @PostMapping("/food")
    public ResponseEntity<FoodRecommendResponse> foodRecommend(@RequestBody FoodRecommendRequest request){
        FoodRecommendResponse response=rService.foodRecommendList(request.getSakeNo());
        return ResponseEntity.ok(response);
    }

//    @PostMapping("/sake/test")
//    public ResponseEntity<List<SakeRerankItem>> sakeRecommendTest(@RequestBody SakeRecommendRequest request){
//        SakeRecommendCondition condition=gService.analyzeFood(request.getFood());
//        List<SakeRecommendCandidate> candidates=rMapper.getSakeRecommendCandidates(condition);
//
//        List<SakeRerankItem> recommendations=gService.rerankSake(
//                request.getFood(),
//                condition,
//                candidates
//        );
//
//        return ResponseEntity.ok(recommendations);
//    }

    @PostMapping("/sake")
    public ResponseEntity<SakeRecommendResponse> sakeRecommend(@RequestBody SakeRecommendRequest request){
        SakeRecommendResponse response=rService.sakeRecommendList(request.getFood());
        return ResponseEntity.ok(response);
    }


}

package com.sakezuki.backend.recommend.service;

import com.sakezuki.backend.recommend.dto.RecommendedFoodResponse;
import com.sakezuki.backend.recommend.dto.SakeRecommendCondition;
import com.sakezuki.backend.sake.dto.SakeDetailResponse;

import java.util.List;

public interface GeminiRecommendService {
    public List<RecommendedFoodResponse> recommendFood(SakeDetailResponse sake);
    public SakeRecommendCondition analyzeFood(String food);
}

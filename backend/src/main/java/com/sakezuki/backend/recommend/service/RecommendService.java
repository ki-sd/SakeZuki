package com.sakezuki.backend.recommend.service;

import com.sakezuki.backend.recommend.dto.FoodRecommendResponse;

import java.util.List;

public interface RecommendService {
    public FoodRecommendResponse foodRecommendList(Long sakeNo);
}

package com.sakezuki.backend.recommend.dto;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Getter
@Builder
public class FoodRecommendResponse {
    private Long sakeNo;
    private List<RecommendedFoodResponse> recommends;
}

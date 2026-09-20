package com.sakezuki.backend.recommend.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

@Getter
@NoArgsConstructor
@AllArgsConstructor
public class AiFoodRecommendResponse {
    private List<RecommendedFoodResponse> recommends;
}

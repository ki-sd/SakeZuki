package com.sakezuki.backend.recommend.dto;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Getter
@Builder
public class SakeRecommendResponse {
    private String food;
    private List<RecommendedSakeResponse> recommendations;
}

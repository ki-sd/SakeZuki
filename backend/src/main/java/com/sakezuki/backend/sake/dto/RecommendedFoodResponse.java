package com.sakezuki.backend.sake.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class RecommendedFoodResponse {
    private String name;
    private String reason;
}

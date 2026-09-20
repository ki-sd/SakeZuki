package com.sakezuki.backend.recommend.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class SakeRecommendCondition {
    private List<String> sakeTypes;
    private Double sakeMeterMin;
    private Double sakeMeterMax;
    private Double acidityMin;
    private Double acidityMax;
    private Double polishingRatioMax;
    private String reason;
}
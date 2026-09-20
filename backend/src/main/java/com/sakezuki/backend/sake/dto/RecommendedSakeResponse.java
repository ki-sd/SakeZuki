package com.sakezuki.backend.sake.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class RecommendedSakeResponse {
    private Long no;
    private String nameKo;
    private String nameJa;
    private String imageUrl;
    private String sakeType;
    private String prefecture;
    private String polishingRatio;
    private String acidity;
    private String alcoholPercentage;
    private String breweryNameKo;
    private String reason;
}

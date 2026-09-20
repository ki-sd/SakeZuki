package com.sakezuki.backend.sake.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SakeDetailResponse {
    private Long no;
    private String nameJa;
    private String nameKana;
    private String nameKo;
    private String sakeType;
    private String rice;
    private String riceKo;
    private String polishingRatio;
    private String yeast;
    private String yeastKo;
    private String sakeMeterValue;
    private String acidity;
    private String alcoholPercentage;
    private String imageUrl;

    private BrandResponse brand;
    private BreweryResponse brewery;
}

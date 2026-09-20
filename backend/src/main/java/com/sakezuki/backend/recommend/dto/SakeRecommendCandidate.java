package com.sakezuki.backend.recommend.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class SakeRecommendCandidate {
    private Long no;
    private String nameKo;
    private String nameJa;
    private String sakeType;
    private String riceKo;
    private String rice;
    private String polishingRatio;
    private String yeastKo;
    private String yeast;
    private String sakeMeterValue;
    private String acidity;
    private String alcoholPercentage;
    private String breweryNameKo;
    private String prefecture;
}

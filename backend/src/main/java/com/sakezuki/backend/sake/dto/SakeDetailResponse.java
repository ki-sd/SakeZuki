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
    private String polishingRatio;
    private String yeast;
    private String sakeMeterValue;
    private String acidity;
    private String alcoholPercentage;
    private String imageUrl;

    private String brandNameKo;
    private String breweryNameKo;
    private String prefecture;
}

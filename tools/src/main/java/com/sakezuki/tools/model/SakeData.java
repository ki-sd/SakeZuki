package com.sakezuki.tools.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SakeData {
    private Long sourceId;

    private String nameJa;
    private String nameKana;

    private String prefecture;
    private String breweryName;
    private String brandName;

    private String sakeType;
    private String rice;
    private String polishingRatio;
    private String yeast;
    private String sakeMeterValue;
    private String acidity;
    private String alcoholPercentage;

    private String breweryUrl;
    private String brandUrl;
}

package com.sakezuki.tools.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BrandData {

    private String sourceId;

    private String nameJa;
    private String nameKana;

    private String breweryName;
    private String breweryUrl;

    private String prefecture;
}
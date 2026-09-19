package com.sakezuki.backend.sake.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SakeListResponse {
    private Long no;
    private String nameJa;
    private String nameKana;
    private String nameKo;
    private String sakeType;
    private String imageUrl;

    private String brandNameKo;
    private String breweryNameKo;
    private String prefecture;
}

package com.sakezuki.backend.sake.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class BrandResponse {
    private String nameJa;
    private String nameKo;
    private String nameKana;
}

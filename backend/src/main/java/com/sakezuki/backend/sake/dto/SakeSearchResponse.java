package com.sakezuki.backend.sake.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class SakeSearchResponse {
    private Long no;
    private String nameKo;
    private String nameJa;
    private String imageUrl;
}

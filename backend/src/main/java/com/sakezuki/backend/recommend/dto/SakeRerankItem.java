package com.sakezuki.backend.recommend.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class SakeRerankItem {
    private Long sakeNo;
    private String reason;
}
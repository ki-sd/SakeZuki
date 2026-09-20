package com.sakezuki.backend.recommend.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class SakeRerankResponse {
    private List<SakeRerankItem> recommendations;
}
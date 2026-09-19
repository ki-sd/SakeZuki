package com.sakezuki.backend.sake.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class BreweryResponse {
    private String nameJa;
    private String nameKana;
    private String nameKo;
    private String corporationName;
    private String foundedYear;
    private String ceo;
    private String prefecture;
    private String address;
    private String post;
    private String phone;
    private String fax;
    private String email;
    private String website;
    private Boolean tourAvailable;
    private Double latitude;
    private Double longitude;
}

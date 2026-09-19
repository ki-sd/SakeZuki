package com.sakezuki.tools.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BreweryData {

    private String sourceId;

    private String nameJa;
    private String nameKana;

    private String corporationName;
    private String foundedYear;

    private String prefecture;
    private String address;
    private String post;

    private String phone;
    private String website;

    private String tourAvailable;

    private Double latitude;
    private Double longitude;
}
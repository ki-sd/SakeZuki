package com.sakezuki.tools.repository;

import com.sakezuki.tools.model.BreweryData;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

public class BreweryRepository {

    private final Connection conn;

    public BreweryRepository(
            Connection conn
    ) {
        this.conn = conn;
    }


    public long save(
            BreweryData brewery
    ) throws SQLException {

        String sql = """
                INSERT INTO BREWERY (
                    source_id,
                    name_ja,
                    name_kana,
                    corporation_name,
                    founded_year,
                    prefecture,
                    address,
                    post,
                    phone,
                    website,
                    tour_available,
                    latitude,
                    longitude
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)

                ON DUPLICATE KEY UPDATE
                    no = LAST_INSERT_ID(no),
                    name_ja = VALUES(name_ja),
                    name_kana = VALUES(name_kana),
                    corporation_name = VALUES(corporation_name),
                    founded_year = VALUES(founded_year),
                    prefecture = VALUES(prefecture),
                    address = VALUES(address),
                    post = VALUES(post),
                    phone = VALUES(phone),
                    website = VALUES(website),
                    tour_available = VALUES(tour_available),
                    latitude = VALUES(latitude),
                    longitude = VALUES(longitude)
                """;


        try (
                PreparedStatement ps =
                        conn.prepareStatement(
                                sql,
                                Statement.RETURN_GENERATED_KEYS
                        )
        ) {

            ps.setString(
                    1,
                    brewery.getSourceId()
            );

            ps.setString(
                    2,
                    brewery.getNameJa()
            );

            ps.setString(
                    3,
                    brewery.getNameKana()
            );

            ps.setString(
                    4,
                    brewery.getCorporationName()
            );

            ps.setString(
                    5,
                    brewery.getFoundedYear()
            );

            ps.setString(
                    6,
                    brewery.getPrefecture()
            );

            ps.setString(
                    7,
                    brewery.getAddress()
            );

            ps.setString(
                    8,
                    brewery.getPost()
            );

            ps.setString(
                    9,
                    brewery.getPhone()
            );

            ps.setString(
                    10,
                    brewery.getWebsite()
            );

            ps.setString(
                    11,
                    brewery.getTourAvailable()
            );

            ps.setObject(
                    12,
                    brewery.getLatitude()
            );

            ps.setObject(
                    13,
                    brewery.getLongitude()
            );


            ps.executeUpdate();


            try (
                    ResultSet rs =
                            ps.getGeneratedKeys()
            ) {

                if (rs.next()) {

                    return rs.getLong(1);
                }
            }


            throw new SQLException(
                    "BREWERY PK 조회 실패"
            );
        }
    }
}
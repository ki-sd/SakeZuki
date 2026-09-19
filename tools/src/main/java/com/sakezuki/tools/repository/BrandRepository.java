package com.sakezuki.tools.repository;

import com.sakezuki.tools.model.BrandData;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

public class BrandRepository {

    private final Connection conn;

    public BrandRepository(
            Connection conn
    ) {
        this.conn = conn;
    }


    public long save(
            BrandData brand,
            long breweryNo
    ) throws SQLException {

        String sql = """
                INSERT INTO BRAND (
                    source_id,
                    brewery_no,
                    name_ja,
                    name_kana
                )
                VALUES (?, ?, ?, ?)

                ON DUPLICATE KEY UPDATE
                    no = LAST_INSERT_ID(no),
                    brewery_no = VALUES(brewery_no),
                    name_ja = VALUES(name_ja),
                    name_kana = VALUES(name_kana)
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
                    brand.getSourceId()
            );

            ps.setLong(
                    2,
                    breweryNo
            );

            ps.setString(
                    3,
                    brand.getNameJa()
            );

            ps.setString(
                    4,
                    brand.getNameKana()
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
                    "BRAND PK 조회 실패"
            );
        }
    }
}
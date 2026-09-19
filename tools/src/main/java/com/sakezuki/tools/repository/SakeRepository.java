package com.sakezuki.tools.repository;

import com.sakezuki.tools.model.SakeData;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

public class SakeRepository {

    private final Connection conn;

    public SakeRepository(
            Connection conn
    ) {
        this.conn = conn;
    }


    public long save(
            SakeData sake,
            long breweryNo,
            long brandNo
    ) throws SQLException {

        String sql = """
                INSERT INTO SAKE (
                    source_id,
                    brewery_no,
                    brand_no,
                    name_ja,
                    name_kana,
                    sake_type,
                    rice,
                    polishing_ratio,
                    yeast,
                    sake_meter_value,
                    acidity,
                    alcohol_percentage
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)

                ON DUPLICATE KEY UPDATE
                    no = LAST_INSERT_ID(no),
                    brewery_no = VALUES(brewery_no),
                    brand_no = VALUES(brand_no),
                    name_ja = VALUES(name_ja),
                    name_kana = VALUES(name_kana),
                    sake_type = VALUES(sake_type),
                    rice = VALUES(rice),
                    polishing_ratio = VALUES(polishing_ratio),
                    yeast = VALUES(yeast),
                    sake_meter_value = VALUES(sake_meter_value),
                    acidity = VALUES(acidity),
                    alcohol_percentage = VALUES(alcohol_percentage)
                """;


        try (
                PreparedStatement ps =
                        conn.prepareStatement(
                                sql,
                                Statement.RETURN_GENERATED_KEYS
                        )
        ) {

            ps.setLong(
                    1,
                    sake.getSourceId()
            );

            ps.setLong(
                    2,
                    breweryNo
            );

            ps.setLong(
                    3,
                    brandNo
            );

            ps.setString(
                    4,
                    sake.getNameJa()
            );

            ps.setString(
                    5,
                    sake.getNameKana()
            );

            ps.setString(
                    6,
                    sake.getSakeType()
            );

            ps.setString(
                    7,
                    sake.getRice()
            );

            ps.setString(
                    8,
                    sake.getPolishingRatio()
            );

            ps.setString(
                    9,
                    sake.getYeast()
            );

            ps.setString(
                    10,
                    sake.getSakeMeterValue()
            );

            ps.setString(
                    11,
                    sake.getAcidity()
            );

            ps.setString(
                    12,
                    sake.getAlcoholPercentage()
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
                    "SAKE PK 조회 실패"
            );

        }

    }
    public List<Long> findUncheckedImageSourceIds() throws SQLException {
        String sql="""
            SELECT source_id
            FROM SAKE
            WHERE image_checked='N'
            ORDER BY no
            """;

        List<Long> sourceIds=new ArrayList<>();

        try (PreparedStatement pstmt=conn.prepareStatement(sql);
             ResultSet rs=pstmt.executeQuery()) {
            while (rs.next()) {
                sourceIds.add(rs.getLong("source_id"));
            }
        }

        return sourceIds;
    }

    public void updateImageUrl(Long sourceId,String imageUrl) throws SQLException {
        String sql="""
            UPDATE SAKE
            SET image_url=?,image_checked='Y'
            WHERE source_id=?
            """;

        try (PreparedStatement pstmt=conn.prepareStatement(sql)) {
            pstmt.setString(1,imageUrl);
            pstmt.setLong(2,sourceId);
            pstmt.executeUpdate();
        }
    }
}
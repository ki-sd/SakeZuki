package com.sakezuki.tools.repository;

import com.sakezuki.tools.model.BrandTranslationData;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public class TranslationRepository {

    private final Connection conn;

    public TranslationRepository(Connection conn){
        this.conn=conn;
    }

    // 아직 번역되지 않은 브랜드를 배치 단위로 가져온다.
    public List<BrandTranslationData> findUntranslatedBrands(int limit) throws SQLException{
        String sql="""
            SELECT no,name_ja,name_kana
            FROM BRAND
            WHERE name_ko IS NULL
            ORDER BY no
            LIMIT ?
            """;

        List<BrandTranslationData> brands=new ArrayList<>();

        try(PreparedStatement pstmt=conn.prepareStatement(sql)){
            pstmt.setInt(1,limit);

            try(ResultSet rs=pstmt.executeQuery()){
                while(rs.next()){
                    brands.add(new BrandTranslationData(
                            rs.getLong("no"),
                            rs.getString("name_ja"),
                            rs.getString("name_kana")
                    ));
                }
            }
        }

        return brands;
    }

    public void updateBrandNameKo(long no,String nameKo) throws SQLException{
        String sql="""
            UPDATE BRAND
            SET name_ko=?
            WHERE no=? AND name_ko IS NULL
            """;

        try(PreparedStatement pstmt=conn.prepareStatement(sql)){
            pstmt.setString(1,nameKo);
            pstmt.setLong(2,no);
            pstmt.executeUpdate();
        }
    }

    public int countUntranslatedBrands() throws SQLException{
        String sql="""
            SELECT COUNT(*)
            FROM BRAND
            WHERE name_ko IS NULL
            """;

        try(PreparedStatement pstmt=conn.prepareStatement(sql);
            ResultSet rs=pstmt.executeQuery()){
            rs.next();
            return rs.getInt(1);
        }
    }
}
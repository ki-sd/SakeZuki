package com.sakezuki.tools.repository;

import com.sakezuki.tools.model.BrandTranslationData;
import com.sakezuki.tools.model.SakeTranslationData;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

// 아직 *_ko가 비어 있는 원문만 꺼내고 갱신 시에도 IS NULL 조건을 둔다.
// 중간에 멈춘 번역을 재실행해도 이미 저장된 한국어 값을 덮어쓰지 않기 위한 경계다.
public class TranslationRepository {
    private final Connection conn;

    public TranslationRepository(Connection conn){
        this.conn=conn;
    }

    // BRAND

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

    // SAKE NAME

    public List<SakeTranslationData> findUntranslatedSakeNames(int limit) throws SQLException{
        String sql="""
            SELECT MIN(no) AS no,name_ja,name_kana
            FROM SAKE
            WHERE name_ko IS NULL
              AND name_ja IS NOT NULL
              AND TRIM(name_ja)<>''
            GROUP BY name_ja,name_kana
            ORDER BY MIN(no)
            LIMIT ?
            """;

        List<SakeTranslationData> sakes=new ArrayList<>();

        try(PreparedStatement pstmt=conn.prepareStatement(sql)){
            pstmt.setInt(1,limit);

            try(ResultSet rs=pstmt.executeQuery()){
                while(rs.next()){
                    sakes.add(new SakeTranslationData(
                            rs.getLong("no"),
                            rs.getString("name_ja"),
                            rs.getString("name_kana")
                    ));
                }
            }
        }

        return sakes;
    }

    public void updateSakeNameKo(String nameJa,String nameKana,String nameKo) throws SQLException{
        String sql;

        if(nameKana==null){
            sql="""
                UPDATE SAKE
                SET name_ko=?
                WHERE name_ja=?
                  AND name_kana IS NULL
                  AND name_ko IS NULL
                """;
        }else{
            sql="""
                UPDATE SAKE
                SET name_ko=?
                WHERE name_ja=?
                  AND name_kana=?
                  AND name_ko IS NULL
                """;
        }

        try(PreparedStatement pstmt=conn.prepareStatement(sql)){
            pstmt.setString(1,nameKo);
            pstmt.setString(2,nameJa);

            if(nameKana!=null){
                pstmt.setString(3,nameKana);
            }

            pstmt.executeUpdate();
        }
    }

    public int countUntranslatedSakeNames() throws SQLException{
        String sql="""
            SELECT COUNT(*)
            FROM (
                SELECT name_ja,name_kana
                FROM SAKE
                WHERE name_ko IS NULL
                  AND name_ja IS NOT NULL
                  AND TRIM(name_ja)<>''
                GROUP BY name_ja,name_kana
            ) t
            """;

        try(PreparedStatement pstmt=conn.prepareStatement(sql);
            ResultSet rs=pstmt.executeQuery()){
            rs.next();
            return rs.getInt(1);
        }
    }

    // RICE

    public List<String> findUntranslatedRice(int limit) throws SQLException{
        String sql="""
            SELECT DISTINCT rice
            FROM SAKE
            WHERE rice IS NOT NULL
              AND TRIM(rice)<>''
              AND rice_ko IS NULL
            ORDER BY rice
            LIMIT ?
            """;

        List<String> values=new ArrayList<>();

        try(PreparedStatement pstmt=conn.prepareStatement(sql)){
            pstmt.setInt(1,limit);

            try(ResultSet rs=pstmt.executeQuery()){
                while(rs.next()){
                    values.add(rs.getString("rice"));
                }
            }
        }

        return values;
    }

    public void updateRiceKo(String rice,String riceKo) throws SQLException{
        String sql="""
            UPDATE SAKE
            SET rice_ko=?
            WHERE rice=?
              AND rice_ko IS NULL
            """;

        try(PreparedStatement pstmt=conn.prepareStatement(sql)){
            pstmt.setString(1,riceKo);
            pstmt.setString(2,rice);
            pstmt.executeUpdate();
        }
    }

    public int countUntranslatedRice() throws SQLException{
        String sql="""
            SELECT COUNT(DISTINCT rice)
            FROM SAKE
            WHERE rice IS NOT NULL
              AND TRIM(rice)<>''
              AND rice_ko IS NULL
            """;

        try(PreparedStatement pstmt=conn.prepareStatement(sql);
            ResultSet rs=pstmt.executeQuery()){
            rs.next();
            return rs.getInt(1);
        }
    }

    // YEAST

    public List<String> findUntranslatedYeast(int limit) throws SQLException{
        String sql="""
            SELECT DISTINCT yeast
            FROM SAKE
            WHERE yeast IS NOT NULL
              AND TRIM(yeast)<>''
              AND yeast_ko IS NULL
            ORDER BY yeast
            LIMIT ?
            """;

        List<String> values=new ArrayList<>();

        try(PreparedStatement pstmt=conn.prepareStatement(sql)){
            pstmt.setInt(1,limit);

            try(ResultSet rs=pstmt.executeQuery()){
                while(rs.next()){
                    values.add(rs.getString("yeast"));
                }
            }
        }

        return values;
    }

    public void updateYeastKo(String yeast,String yeastKo) throws SQLException{
        String sql="""
            UPDATE SAKE
            SET yeast_ko=?
            WHERE yeast=?
              AND yeast_ko IS NULL
            """;

        try(PreparedStatement pstmt=conn.prepareStatement(sql)){
            pstmt.setString(1,yeastKo);
            pstmt.setString(2,yeast);
            pstmt.executeUpdate();
        }
    }

    public int countUntranslatedYeast() throws SQLException{
        String sql="""
            SELECT COUNT(DISTINCT yeast)
            FROM SAKE
            WHERE yeast IS NOT NULL
              AND TRIM(yeast)<>''
              AND yeast_ko IS NULL
            """;

        try(PreparedStatement pstmt=conn.prepareStatement(sql);
            ResultSet rs=pstmt.executeQuery()){
            rs.next();
            return rs.getInt(1);
        }
    }

    // DATA CLEANUP

    public List<String> findRiceValues() throws SQLException{
        String sql="""
            SELECT DISTINCT rice
            FROM SAKE
            WHERE rice IS NOT NULL
              AND TRIM(rice)<>''
            """;

        return findDistinctValues(sql,"rice");
    }

    public List<String> findYeastValues() throws SQLException{
        String sql="""
            SELECT DISTINCT yeast
            FROM SAKE
            WHERE yeast IS NOT NULL
              AND TRIM(yeast)<>''
            """;

        return findDistinctValues(sql,"yeast");
    }

    private List<String> findDistinctValues(String sql,String column) throws SQLException{
        List<String> values=new ArrayList<>();

        try(PreparedStatement pstmt=conn.prepareStatement(sql);
            ResultSet rs=pstmt.executeQuery()){

            while(rs.next()){
                values.add(rs.getString(column));
            }
        }

        return values;
    }

    public void replaceRiceValue(String original,String cleaned) throws SQLException{
        String sql="""
            UPDATE SAKE
            SET rice=?
            WHERE rice=?
            """;

        try(PreparedStatement pstmt=conn.prepareStatement(sql)){
            pstmt.setString(1,cleaned);
            pstmt.setString(2,original);
            pstmt.executeUpdate();
        }
    }

    public void replaceYeastValue(String original,String cleaned) throws SQLException{
        String sql="""
            UPDATE SAKE
            SET yeast=?
            WHERE yeast=?
            """;

        try(PreparedStatement pstmt=conn.prepareStatement(sql)){
            pstmt.setString(1,cleaned);
            pstmt.setString(2,original);
            pstmt.executeUpdate();
        }
    }
}
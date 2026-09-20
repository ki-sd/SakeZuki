package com.sakezuki.tools.recommend;



import com.sakezuki.tools.util.EnvLoader;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

public class SakeRecommendProfileGenerator {

    private static final int BATCH_SIZE=500;

    public static void main(String[] args) throws Exception{
        String url="jdbc:mysql://localhost:3306/"+ EnvLoader.get("MYSQL_DATABASE")
                +"?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Seoul";
        String username=EnvLoader.get("MYSQL_USER");
        String password=EnvLoader.get("MYSQL_PASSWORD");

        try(Connection conn=DriverManager.getConnection(url,username,password)){
            conn.setAutoCommit(false);

            try{
                generate(conn);
                conn.commit();
            }catch(Exception e){
                conn.rollback();
                throw e;
            }
        }
    }

    private static void generate(Connection conn) throws Exception{
        String selectSql="""
                SELECT no,sake_meter_value,acidity,polishing_ratio
                FROM SAKE
                ORDER BY no
                """;

        String insertSql="""
                INSERT INTO SAKE_RECOMMEND_PROFILE(
                    sake_no,
                    sake_meter_min,
                    sake_meter_max,
                    acidity_min,
                    acidity_max,
                    polishing_ratio_min,
                    polishing_ratio_max
                )
                VALUES(?,?,?,?,?,?,?)
                """;

        int count=0;

        try(
                Statement stmt=conn.createStatement();
                ResultSet rs=stmt.executeQuery(selectSql);
                PreparedStatement pstmt=conn.prepareStatement(insertSql)
        ){
            while(rs.next()){
                long sakeNo=rs.getLong("no");

                SakeValueParser.Range sakeMeter=
                        SakeValueParser.parse(rs.getString("sake_meter_value"));

                SakeValueParser.Range acidity=
                        SakeValueParser.parse(rs.getString("acidity"));

                SakeValueParser.Range polishingRatio=
                        SakeValueParser.parse(rs.getString("polishing_ratio"));

                pstmt.setLong(1,sakeNo);
                setDecimal(pstmt,2,sakeMeter.getMin());
                setDecimal(pstmt,3,sakeMeter.getMax());
                setDecimal(pstmt,4,acidity.getMin());
                setDecimal(pstmt,5,acidity.getMax());
                setDecimal(pstmt,6,polishingRatio.getMin());
                setDecimal(pstmt,7,polishingRatio.getMax());

                pstmt.addBatch();
                count++;

                if(count%BATCH_SIZE==0){
                    pstmt.executeBatch();
                    System.out.println(count+"건 처리");
                }
            }

            pstmt.executeBatch();
        }

        System.out.println("총 "+count+"건 처리 완료");
    }

    private static void setDecimal(
            PreparedStatement pstmt,
            int index,
            BigDecimal value
    ) throws Exception{
        if(value==null){
            pstmt.setNull(index,java.sql.Types.DECIMAL);
        }else{
            pstmt.setBigDecimal(index,value);
        }
    }
}
package com.sakezuki.tools;

import com.sakezuki.tools.repository.TranslationRepository;
import com.sakezuki.tools.translator.BrandTranslator;
import com.sakezuki.tools.util.EnvLoader;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;

public class TranslationMain {

    public static void main(String[] args){
        // Windows 콘솔 한글 깨짐 방지
        System.setOut(new PrintStream(System.out,true,StandardCharsets.UTF_8));
        System.setErr(new PrintStream(System.err,true,StandardCharsets.UTF_8));

        String database=EnvLoader.get("MYSQL_DATABASE");
        String user=EnvLoader.get("MYSQL_USER");
        String password=EnvLoader.get("MYSQL_PASSWORD");

        String url="jdbc:mysql://localhost:13306/"+database;

        try(Connection conn=DriverManager.getConnection(url,user,password)){
            TranslationRepository repository=new TranslationRepository(conn);
            BrandTranslator translator=new BrandTranslator(repository);

            translator.translateAll();

        }catch(Exception e){
            System.err.println("번역 실행 실패: "+e.getMessage());
            e.printStackTrace();
        }
    }
}
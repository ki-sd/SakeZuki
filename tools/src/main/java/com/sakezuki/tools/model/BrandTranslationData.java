package com.sakezuki.tools.model;

public class BrandTranslationData {

    private long no;
    private String nameJa;
    private String nameKana;
    private String nameKo;

    public BrandTranslationData(long no,String nameJa,String nameKana){
        this.no=no;
        this.nameJa=nameJa;
        this.nameKana=nameKana;
    }

    public long getNo(){
        return no;
    }

    public String getNameJa(){
        return nameJa;
    }

    public String getNameKana(){
        return nameKana;
    }

    public String getNameKo(){
        return nameKo;
    }

    public void setNameKo(String nameKo){
        this.nameKo=nameKo;
    }
}
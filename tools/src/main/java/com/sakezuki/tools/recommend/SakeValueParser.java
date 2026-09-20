package com.sakezuki.tools.recommend;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.math.BigDecimal;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SakeValueParser {

    private static final Pattern NUMBER_PATTERN=Pattern.compile("-?\\d+(?:\\.\\d+)?");

    public static Range parse(String value){
        if(value==null || value.isBlank()){
            return new Range(null,null);
        }

        String text=value.trim()
                .replace("−","-")
                .replace("‐","-");

        Matcher matcher=NUMBER_PATTERN.matcher(text);

        BigDecimal first=null;
        BigDecimal second=null;

        if(matcher.find()){
            first=new BigDecimal(matcher.group());
        }

        if(matcher.find()){
            second=new BigDecimal(matcher.group());
        }

        if(first==null){
            return new Range(null,null);
        }

        // 1%未満 같은 값
        if(text.contains("未満")){
            return new Range(null,first);
        }

        // 일반 단일값
        if(second==null){
            return new Range(first,first);
        }

        // 범위가 역순으로 들어온 경우도 정렬
        return first.compareTo(second)<=0
                ? new Range(first,second)
                : new Range(second,first);
    }

    @Getter
    @AllArgsConstructor
    public static class Range {
        private BigDecimal min;
        private BigDecimal max;
    }
}
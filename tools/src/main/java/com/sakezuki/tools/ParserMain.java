package com.sakezuki.tools;

import com.sakezuki.tools.recommend.SakeValueParser;

import static com.sakezuki.tools.recommend.SakeValueParser.parse;

public class ParserMain {
    public static void main(String[] args){
        print("3.5");
        print("2.0～3.0");
        print("-65.0～-75.0");
        print("60%");
        print("1%未満");
        print("-154.0");
        print("66.0");
        print(null);
    }

    private static void print(String value){
        SakeValueParser.Range range=parse(value);

        System.out.println(
                value+" -> "+range.getMin()+" / "+range.getMax()
        );
    }
}

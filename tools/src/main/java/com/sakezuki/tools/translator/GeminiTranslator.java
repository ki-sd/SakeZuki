package com.sakezuki.tools.translator;

import com.google.genai.Client;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.Schema;
import com.google.genai.types.Type;
import com.sakezuki.tools.util.EnvLoader;

import java.util.List;
import java.util.Map;

public class GeminiTranslator {

    private static final String MODEL="gemini-3.5-flash";

    private final Client client;

    public GeminiTranslator(){
        this.client=Client.builder()
                .apiKey(EnvLoader.get("GEMINI_API_KEY"))
                .build();
    }

    public String translateBrands(String prompt){
        // [{no:1,nameKo:"..."}, ...] 형태만 반환하도록 제한
        Schema schema=Schema.builder()
                .type(Type.Known.ARRAY)
                .items(
                        Schema.builder()
                                .type(Type.Known.OBJECT)
                                .properties(Map.of(
                                        "no",Schema.builder()
                                                .type(Type.Known.INTEGER)
                                                .build(),
                                        "nameKo",Schema.builder()
                                                .type(Type.Known.STRING)
                                                .build()
                                ))
                                .required(List.of("no","nameKo"))
                                .build()
                )
                .build();

        GenerateContentConfig config=GenerateContentConfig.builder()
                .responseMimeType("application/json")
                .responseSchema(schema)
                .build();

        GenerateContentResponse response=client.models.generateContent(
                MODEL,
                prompt,
                config
        );

        String result=response.text();

        if(result==null || result.isBlank()){
            throw new IllegalStateException("Gemini 응답이 비어 있습니다.");
        }

        return result;
    }
}
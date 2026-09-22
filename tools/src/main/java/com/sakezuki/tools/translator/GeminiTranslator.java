package com.sakezuki.tools.translator;

import com.google.genai.Client;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.Schema;
import com.google.genai.types.Type;
import com.sakezuki.tools.util.EnvLoader;

import java.util.List;
import java.util.Map;

// 번역 요청의 JSON 스키마와 재시도를 한곳에 모아 브랜드·사케·용어 번역이 같은 응답 규칙을 쓴다.
public class GeminiTranslator {
    private static final String MODEL="gemini-3.5-flash";
    private static final int MAX_RETRY=3;

    private final Client client;

    public GeminiTranslator(){
        this.client=Client.builder()
                .apiKey(EnvLoader.get("GEMINI_API_KEY"))
                .build();
    }

    public String translateBrands(String prompt){
        return generate(prompt,createNameSchema());
    }

    public String translateSakeNames(String prompt){
        return generate(prompt,createNameSchema());
    }

    public String translateTerms(String prompt){
        Schema schema=Schema.builder()
                .type(Type.Known.ARRAY)
                .items(
                        Schema.builder()
                                .type(Type.Known.OBJECT)
                                .properties(Map.of(
                                        "id",Schema.builder()
                                                .type(Type.Known.INTEGER)
                                                .build(),
                                        "valueKo",Schema.builder()
                                                .type(Type.Known.STRING)
                                                .build()
                                ))
                                .required(List.of("id","valueKo"))
                                .build()
                )
                .build();

        return generate(prompt,schema);
    }

    private Schema createNameSchema(){
        return Schema.builder()
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
    }

    // JSON MIME과 스키마는 응답 형태를 유도하고, 실제 값의 타당성은 호출자가 별도로 검증한다.
    // 일시적 API 실패에는 제한된 횟수로 간격을 늘려 재시도한다.
    private String generate(String prompt,Schema schema){
        GenerateContentConfig config=GenerateContentConfig.builder()
                .responseMimeType("application/json")
                .responseSchema(schema)
                .temperature(0.1f)
                .build();

        Exception lastException=null;

        for(int attempt=1;attempt<=MAX_RETRY;attempt++){
            try{
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
            }catch(Exception e){
                lastException=e;

                System.err.println(
                        "Gemini 호출 실패 ("+
                                attempt+"/"+MAX_RETRY+"): "+
                                e.getMessage()
                );

                if(attempt<MAX_RETRY){
                    try{
                        Thread.sleep(2000L*attempt);
                    }catch(InterruptedException interruptedException){
                        Thread.currentThread().interrupt();

                        throw new IllegalStateException(
                                "Gemini 재시도 대기 중 중단되었습니다.",
                                interruptedException
                        );
                    }
                }
            }
        }

        throw new IllegalStateException(
                "Gemini 호출이 "+MAX_RETRY+"회 모두 실패했습니다.",
                lastException
        );
    }
}
package com.sakezuki.tools.translator;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sakezuki.tools.model.BrandTranslationData;
import com.sakezuki.tools.model.BrandTranslationResult;
import com.sakezuki.tools.repository.TranslationRepository;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class BrandTranslator {

    private static final int BATCH_SIZE=50;

    private final TranslationRepository repository;
    private final GeminiTranslator geminiTranslator;
    private final ObjectMapper objectMapper=new ObjectMapper();

    public BrandTranslator(TranslationRepository repository){
        this.repository=repository;
        this.geminiTranslator=new GeminiTranslator();
    }

    public void translateAll() throws Exception{
        int total=repository.countUntranslatedBrands();
        int complete=0;

        System.out.println("BRAND 번역 시작");
        System.out.println("번역 대상: "+total);

        while(true){
            List<BrandTranslationData> brands=repository.findUntranslatedBrands(BATCH_SIZE);

            if(brands.isEmpty()){
                break;
            }

            String prompt=createPrompt(brands);
            String json=geminiTranslator.translateBrands(prompt);

            List<BrandTranslationResult> results=objectMapper.readValue(
                    json,
                    new TypeReference<List<BrandTranslationResult>>(){}
            );

            validateResults(brands,results);

            // 한 배치가 전부 정상일 때만 DB에 반영한다.
            for(BrandTranslationResult result:results){
                repository.updateBrandNameKo(result.getNo(),result.getNameKo());
            }

            complete+=results.size();

            System.out.println(
                    "[BRAND] "+complete+"/"+total+
                            " | 남음="+repository.countUntranslatedBrands()
            );

        }

        System.out.println("BRAND 번역 완료");
    }

    private void validateResults(
            List<BrandTranslationData> brands,
            List<BrandTranslationResult> results
    ){
        if(results.size()!=brands.size()){
            throw new IllegalStateException(
                    "Gemini 응답 개수 불일치: 요청="+brands.size()+", 응답="+results.size()
            );
        }

        Map<Long,BrandTranslationData> requested=new HashMap<>();

        for(BrandTranslationData brand:brands){
            requested.put(brand.getNo(),brand);
        }

        Set<Long> returned=new HashSet<>();

        for(BrandTranslationResult result:results){
            if(!requested.containsKey(result.getNo())){
                throw new IllegalStateException(
                        "요청하지 않은 BRAND no 반환: "+result.getNo()
                );
            }

            if(!returned.add(result.getNo())){
                throw new IllegalStateException(
                        "중복 BRAND no 반환: "+result.getNo()
                );
            }

            if(result.getNameKo()==null || result.getNameKo().isBlank()){
                throw new IllegalStateException(
                        "빈 한국어 브랜드명 반환: "+result.getNo()
                );
            }
        }

        if(returned.size()!=requested.size()){
            throw new IllegalStateException("일부 BRAND 번역 결과가 누락되었습니다.");
        }
    }

    private String createPrompt(List<BrandTranslationData> brands){
        StringBuilder data=new StringBuilder();

        for(int i=0;i<brands.size();i++){
            BrandTranslationData brand=brands.get(i);

            data.append("""
                {
                    "no":%d,
                    "nameJa":"%s",
                    "nameKana":"%s"
                }
                """.formatted(
                    brand.getNo(),
                    escapeJson(brand.getNameJa()),
                    escapeJson(brand.getNameKana())
            ));

            if(i<brands.size()-1){
                data.append(",");
            }
        }

        return """
            일본 사케 브랜드명을 한국어로 표기하는 데이터 정제 작업이다.

            다음 규칙에 따라 각 브랜드의 한국어명을 생성하라.

            규칙:
            1. 브랜드 고유명사는 의미를 번역하지 말고 일본어 발음을 기준으로 한글로 표기한다.
            2. nameKana가 존재하면 nameKana를 발음 판단의 최우선 기준으로 사용한다.
            3. 일본어 장음은 생략하지 않고 한글에서도 발음이 드러나도록 표기한다.
               예: おおみね → 오오미네
               예: こう → 코우
               예: ほう → 호우
            4. 촉음과 요음도 실제 일본어 발음을 최대한 보존한다.
            5. 숫자와 알파벳은 특별한 이유가 없으면 원문을 유지한다.
            6. 원문에 없는 단어나 의미를 임의로 추가하지 않는다.
            7. 입력된 no는 절대 변경하지 않는다.
            8. 모든 입력 항목을 정확히 한 번씩 반환한다.

            번역할 데이터:
            [
            %s
            ]
            """.formatted(data);
    }

    private String escapeJson(String value){
        if(value==null) return "";

        return value
                .replace("\\","\\\\")
                .replace("\"","\\\"")
                .replace("\n","\\n")
                .replace("\r","\\r");
    }
}
package com.sakezuki.backend.recommend.service;

import com.sakezuki.backend.recommend.dto.AiFoodRecommendResponse;
import com.sakezuki.backend.recommend.dto.RecommendedFoodResponse;
import com.sakezuki.backend.sake.dto.SakeDetailResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class GeminiRecommendServiceImpl implements GeminiRecommendService {
    private final ChatClient.Builder builder;

    @Override
    public List<RecommendedFoodResponse> recommendFood(SakeDetailResponse sake) {
        String prompt="""
        당신은 일본 사케와 음식 페어링 전문가입니다.
        아래 사케의 정보를 종합하여 이 사케와 잘 어울리는 음식 3개를 추천하세요.

        [사케 정보]
        사케명: %s (%s)
        종류: %s
        사용 쌀: %s (%s)
        정미보합: %s
        효모: %s (%s)
        일본주도: %s
        산도: %s
        알코올 도수: %s
        양조장: %s (%s)
        지역: %s

        [추천 기준]
        1. 추천 음식은 정확히 3개를 제시한다.
        2. 세 음식은 서로 다른 음식이어야 하며, 지나치게 비슷한 음식만 반복하지 않는다.
        3. 음식명과 추천 이유는 자연스러운 한국어로 작성한다.
        4. 사케 종류, 사용 쌀, 정미보합, 효모, 일본주도, 산도, 알코올 도수 등
           제공된 정보를 종합하여 음식과의 조화를 판단한다.
        5. 필요하면 일본 사케와 양조 특성에 관한 일반적인 전문 지식을 활용할 수 있다.
           단, 제공되지 않은 이 제품 고유의 향, 맛, 수치, 원료, 제조법 등을
           확인된 사실인 것처럼 만들어내지 않는다.
        6. 특정 정보가 '정보없음'인 경우 해당 정보가 존재한다고 추측하지 않는다.
        7. 추천 이유에는 단순히 '잘 어울린다'고만 쓰지 말고,
           어떤 사케 정보와 음식의 어떤 특성을 고려했는지 구체적으로 설명한다.
        8. 단순히 유명하거나 일반적으로 사케와 자주 먹는 음식만 반복하지 말고,
           입력된 사케의 특성을 고려해 적합한 음식을 선정한다.
        9. 음식의 풍미, 지방감, 감칠맛, 산미, 단맛, 염도, 질감 등을 고려하여
           사케와 서로 보완되거나 균형을 이루는 이유를 설명한다.
        10. 사용자가 실제로 음식 페어링을 선택하는 데 도움이 되도록
            과도하게 전문적이거나 추상적인 표현보다 이해하기 쉬운 이유를 제시한다.
        """.formatted(
                sake.getNameKo()!=null?sake.getNameKo():"정보없음",
                sake.getNameJa()!=null?sake.getNameJa():"정보없음",
                sake.getSakeType()!=null?sake.getSakeType():"정보없음",
                sake.getRiceKo()!=null?sake.getRiceKo():"정보없음",
                sake.getRice()!=null?sake.getRice():"정보없음",
                sake.getPolishingRatio()!=null?sake.getPolishingRatio():"정보없음",
                sake.getYeastKo()!=null?sake.getYeastKo():"정보없음",
                sake.getYeast()!=null?sake.getYeast():"정보없음",
                sake.getSakeMeterValue()!=null?sake.getSakeMeterValue():"정보없음",
                sake.getAcidity()!=null?sake.getAcidity():"정보없음",
                sake.getAlcoholPercentage()!=null?sake.getAlcoholPercentage():"정보없음",
                sake.getBrewery()!=null && sake.getBrewery().getNameKo()!=null
                        ? sake.getBrewery().getNameKo():"정보없음",
                sake.getBrewery()!=null && sake.getBrewery().getNameJa()!=null
                        ? sake.getBrewery().getNameJa():"정보없음",
                sake.getBrewery()!=null && sake.getBrewery().getPrefecture()!=null
                        ? sake.getBrewery().getPrefecture():"정보없음"
        );

        AiFoodRecommendResponse response=builder.build()
                .prompt()
                .user(prompt)
                .call()
                .entity(
                        AiFoodRecommendResponse.class,
                        spec->spec
                                .useProviderStructuredOutput()
                                .validateSchema()
                );

        // 검증
        validateResponse(response);

        return response.getRecommends();
    }

    private void validateResponse(AiFoodRecommendResponse response){
        if(response==null || response.getRecommends()==null){
            throw new IllegalStateException("AI 음식 추천 결과가 없습니다.");
        }
        if(response.getRecommends().size()!=3){
            throw new IllegalStateException("AI의 추천 결과가 3개거 아닙니다.");
        }
        for(RecommendedFoodResponse recommend:response.getRecommends()){
            if(recommend.getName()==null || recommend.getName().isBlank()){
                throw new IllegalStateException("추천 음식명이 비어있습니다.");
            }
            if(recommend.getReason()==null || recommend.getReason().isBlank()){
                throw new IllegalStateException("추천 이유가 비어있습니다.");
            }
        }
    }
}

package com.sakezuki.backend.recommend.service;

import com.sakezuki.backend.recommend.dto.*;
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
        // 실제 SAKE 상세값을 프롬프트에 넣고, 없는 필드는 정보없음으로 표시해 추측을 줄인다.
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

        // 구조화 출력으로 DTO 형태를 요청하더라도 필드가 비거나 개수가 틀릴 수 있어 뒤에서 다시 검증한다.
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

        // DB에 저장하기 전에 개수와 사용자에게 보여줄 필수 문구를 확인한다.
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

    @Override
    public SakeRecommendCondition analyzeFood(String food){
        // 첫 AI 호출은 제품을 고르는 단계가 아니라 DB 후보를 찾을 느슨한 조건을 만드는 단계다.
        String prompt="""
            당신은 일본주와 음식 페어링을 분석하는 전문가입니다.

            사용자가 입력한 음식의 맛, 향, 지방감, 감칠맛, 단맛, 짠맛 등을 분석하고
            해당 음식과 잘 어울릴 가능성이 높은 사케를 데이터베이스에서 검색하기 위한
            조건을 생성하세요.

            음식:
            %s

            사용할 수 있는 조건:
            - sakeTypes는 음식과 어울리는 사케 종류를 나타냅니다.
            - sakeTypes는 반드시 아래 값 중에서만 선택하세요.
                純米吟醸酒, 純米酒, 純米大吟醸酒, 大吟醸酒, 本醸造酒, 特別純米酒,
                吟醸酒, 普通酒, 特別本醸造酒, 原酒, 生原酒, スパークリング,
                古酒, リキュール, 純米生原酒, 貴醸酒, 本吟醸酒
                - 위 목록에 없는 영어명이나 새로운 분류명을 만들지 마세요.
                - 음식과의 페어링에 도움이 되는 종류만 선택하세요.
                - 적절한 종류를 특정하기 어렵다면 빈 배열로 반환하세요.
            - sakeMeterMin, sakeMeterMax: 일본주도 범위
            - acidityMin, acidityMax: 산도 범위
            - polishingRatioMin, polishingRatioMax: 정미보합 범위

            주의사항:
            - 이것은 최종 사케 추천이 아니라 DB 후보 검색을 위한 조건입니다.
            - 모든 조건을 반드시 채울 필요는 없습니다.
            - 음식만으로 합리적으로 판단하기 어려운 조건은 null로 두세요.
            - 조건을 지나치게 좁혀 후보가 거의 없어지지 않도록 넓은 범위를 사용하세요.
            - sakeTypes 역시 확실히 도움이 되는 경우에만 지정하세요.
            - reason에는 왜 이런 검색 조건을 선택했는지 간단한 한국어 설명을 작성하세요.
            - 실제 제품명이나 존재하지 않는 제품 정보를 만들어내지 마세요.
            """.formatted(food);

        SakeRecommendCondition condition=builder.build()
                .prompt()
                .user(prompt)
                .call()
                .entity(
                        SakeRecommendCondition.class,
                        spec->spec
                                .useProviderStructuredOutput()
                                .validateSchema()
                );

        if(condition==null){
            throw new IllegalStateException("사케 추천 검색조건 생성에 실패했습니다.");
        }

        return condition;
    }

    @Override
    public List<SakeRerankItem> rerankSake(
            String food,
            SakeRecommendCondition condition,
            List<SakeRecommendCandidate> candidates
    ){
        // 두 번째 AI 호출에는 조회된 후보의 no와 실제 속성만 제공한다.
        // 결과 식별자가 후보 밖으로 나가면 저장하지 않도록 아래에서 교차 검증한다.
        if(candidates==null || candidates.isEmpty()){
            throw new IllegalArgumentException("추천할 사케 후보가 없습니다.");
        }

        StringBuilder candidateText=new StringBuilder();

        for(SakeRecommendCandidate candidate:candidates){
            candidateText.append("""
                [sakeNo=%d]
                이름: %s / %s
                종류: %s
                쌀: %s / %s
                정미보합: %s
                일본주도: %s
                산도: %s
                알코올 도수: %s
                지역: %s

                """.formatted(
                    candidate.getNo(),
                    value(candidate.getNameKo()),
                    value(candidate.getNameJa()),
                    value(candidate.getSakeType()),
                    value(candidate.getRiceKo()),
                    value(candidate.getRice()),
                    value(candidate.getPolishingRatio()),
                    value(candidate.getSakeMeterValue()),
                    value(candidate.getAcidity()),
                    value(candidate.getAlcoholPercentage()),
                    value(candidate.getPrefecture())
            ));
        }

        String prompt="""
            당신은 일본주와 음식 페어링을 분석하는 전문가입니다.

            사용자가 입력한 음식과 DB에서 검색된 사케 후보를 비교하여
            가장 잘 어울리는 사케를 정확히 3개 선택하세요.

            음식:
            %s

            1차 검색조건:
            사케 종류: %s
            일본주도: %s ~ %s
            산도: %s ~ %s
            정미보합: %s ~ %s
            조건 생성 이유: %s

            후보:
            %s

            규칙:
            - 반드시 제공된 후보 안에서만 선택하세요.
            - 정확히 3개의 서로 다른 sakeNo를 선택하세요.
            - sakeNo는 후보에 표시된 값을 그대로 사용하세요.
            - 후보에 없는 사케나 제품 정보를 만들어내지 마세요.
            - DB에 없는 수치, 원료, 제조법 등의 제품 고유 사실을 추측하지 마세요.
            - 1차 검색조건은 후보 검색을 위한 참고 정보이며 절대적인 정답이 아닙니다.
            - 음식의 맛, 향, 감칠맛, 지방감, 단맛, 짠맛 등과 후보의 실제 정보를 종합하여 비교하세요.
            - 값이 없는 항목은 알 수 없는 정보이므로 추측하지 마세요.
            - reason은 사용자에게 보여줄 자연스러운 한국어 추천 이유로 작성하세요.
            - reason에서는 실제 후보 데이터와 일반적인 사케 페어링 원리를 활용할 수 있습니다.
            - 같은 이유를 세 제품에 반복하지 말고 각 제품을 선택한 이유가 드러나도록 작성하세요.
            """.formatted(
                food,
                condition.getSakeTypes(),
                condition.getSakeMeterMin(),
                condition.getSakeMeterMax(),
                condition.getAcidityMin(),
                condition.getAcidityMax(),
                condition.getPolishingRatioMin(),
                condition.getPolishingRatioMax(),
                condition.getReason(),
                candidateText
        );

        SakeRerankResponse response=builder.build()
                .prompt()
                .user(prompt)
                .call()
                .entity(
                        SakeRerankResponse.class,
                        spec->spec
                                .useProviderStructuredOutput()
                                .validateSchema()
                );

        if(response==null
                || response.getRecommendations()==null
                || response.getRecommendations().size()!=3){
            throw new IllegalStateException("사케 추천 결과가 올바르지 않습니다.");
        }

        List<Long> candidateNos=candidates.stream()
                .map(SakeRecommendCandidate::getNo)
                .toList();

        List<Long> resultNos=response.getRecommendations().stream()
                .map(SakeRerankItem::getSakeNo)
                .toList();

        // JSON 형식이 맞아도 중복 번호나 후보 밖 번호는 존재하지 않는 추천과 같은 문제를 만든다.
        if(resultNos.stream().distinct().count()!=3){
            throw new IllegalStateException("중복된 사케가 추천되었습니다.");
        }

        if(!candidateNos.containsAll(resultNos)){
            throw new IllegalStateException("후보에 없는 사케가 추천되었습니다.");
        }

        return response.getRecommendations();
    }

    private String value(Object value){
        return value!=null ? value.toString() : "정보 없음";
    }
}

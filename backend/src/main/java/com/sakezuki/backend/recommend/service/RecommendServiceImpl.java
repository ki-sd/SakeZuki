package com.sakezuki.backend.recommend.service;

import com.sakezuki.backend.recommend.dto.*;
import com.sakezuki.backend.recommend.mapper.RecommendMapper;
import com.sakezuki.backend.sake.dto.SakeDetailResponse;
import com.sakezuki.backend.sake.service.SakeService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class RecommendServiceImpl implements RecommendService {
    private final RecommendMapper rMapper;
    private final SakeService sService;
    private final GeminiRecommendService gService;

    // 같은 sakeNo의 추천을 먼저 DB에서 찾는다. 이미 생성된 결과가 있으면 Gemini 호출을 건너뛴다.
    @Override
    public FoodRecommendResponse foodRecommendList(Long sakeNo) {
        List<RecommendedFoodResponse> recommends=rMapper.getFoodRecommendList(sakeNo);
        if(recommends.isEmpty()){
            SakeDetailResponse sake=sService.getSakeDetail(sakeNo);
            // DB의 실제 상세 정보를 AI에 전달해 제품 고유 사실을 임의로 만들지 않도록 한다.
            // AI 호출
            recommends=gService.recommendFood(sake);
            // 첫 요청의 결과를 저장해 다음 요청은 같은 음식 추천을 재사용한다.
            // DB 저장
            rMapper.insertFoodRecommend(sakeNo,recommends);
        }
        return FoodRecommendResponse.builder()
                .sakeNo(sakeNo)
                .recommends(recommends)
                .build();
    }

    // 음식명은 공백만 정리해 캐시 키로 쓰며, 최초 요청에서만 분석→DB 후보→재선정을 수행한다.
    @Override
    public SakeRecommendResponse sakeRecommendList(String food){
        if(food==null || food.isBlank()){
            throw new IllegalArgumentException("음식명이 비어있습니다.");
        }

        String normalizedFood=food.trim();

        List<RecommendedSakeResponse> recommendations=
                rMapper.getSakeRecommendList(normalizedFood);

        if(recommendations.isEmpty()){

            // Gemini는 후보 검색 조건을 만들고, 실제 후보 제품은 MyBatis가 DB에서 가져온다.
            SakeRecommendCondition condition=gService.analyzeFood(normalizedFood);

            List<SakeRecommendCandidate> candidates=
                    rMapper.getSakeRecommendCandidates(condition);

            if(candidates.isEmpty()){
                throw new IllegalStateException("추천 가능한 사케 후보가 없습니다.");
            }

            // 후보 안에서 고른 식별자와 추천 이유만 저장한다. 응답의 제품 정보는 다시 DB에서 읽는다.
            List<SakeRerankItem> rerankItems=
                    gService.rerankSake(
                            normalizedFood,
                            condition,
                            candidates
                    );

            rMapper.insertSakeRecommend(normalizedFood,rerankItems);

            recommendations=rMapper.getSakeRecommendList(normalizedFood);
        }

        if(recommendations.size()!=3){
            throw new IllegalStateException("사케 추천 결과가 3개가 아닙니다.");
        }

        return SakeRecommendResponse.builder()
                .food(normalizedFood)
                .recommendations(recommendations)
                .build();
    }
}

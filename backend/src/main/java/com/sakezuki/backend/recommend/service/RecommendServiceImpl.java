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

    @Override
    public FoodRecommendResponse foodRecommendList(Long sakeNo) {
        List<RecommendedFoodResponse> recommends=rMapper.getFoodRecommendList(sakeNo);
        if(recommends.isEmpty()){
            SakeDetailResponse sake=sService.getSakeDetail(sakeNo);
            // AI 호출
            recommends=gService.recommendFood(sake);
            // DB 저장
            rMapper.insertFoodRecommend(sakeNo,recommends);
        }
        return FoodRecommendResponse.builder()
                .sakeNo(sakeNo)
                .recommends(recommends)
                .build();
    }

    @Override
    public SakeRecommendResponse sakeRecommendList(String food){
        if(food==null || food.isBlank()){
            throw new IllegalArgumentException("음식명이 비어있습니다.");
        }

        String normalizedFood=food.trim();

        List<RecommendedSakeResponse> recommendations=
                rMapper.getSakeRecommendList(normalizedFood);

        if(recommendations.isEmpty()){

            SakeRecommendCondition condition=gService.analyzeFood(normalizedFood);

            List<SakeRecommendCandidate> candidates=
                    rMapper.getSakeRecommendCandidates(condition);

            if(candidates.isEmpty()){
                throw new IllegalStateException("추천 가능한 사케 후보가 없습니다.");
            }

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

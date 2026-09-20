package com.sakezuki.backend.recommend.service;

import com.sakezuki.backend.recommend.dto.FoodRecommendResponse;
import com.sakezuki.backend.recommend.dto.RecommendedFoodResponse;
import com.sakezuki.backend.recommend.mapper.RecommendMapper;
import com.sakezuki.backend.sake.dto.SakeDetailResponse;
import com.sakezuki.backend.sake.service.SakeService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class RecommendServiceImpl implements RecommendService {
    private final RecommendMapper rMapper;
    private final SakeService sService;
    private final GeminiRecommendService gService;

    @Override
    @Transactional
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
}

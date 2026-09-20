package com.sakezuki.backend.recommend.mapper;

import com.sakezuki.backend.recommend.dto.*;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface RecommendMapper {
    public List<RecommendedFoodResponse> getFoodRecommendList(Long sakeNo);
    public void insertFoodRecommend(@Param("sakeNo")Long sakeNo,
                                    @Param("list")List<RecommendedFoodResponse> list);
    public List<SakeRecommendCandidate> getSakeRecommendCandidates(SakeRecommendCondition condition);

    public List<RecommendedSakeResponse> getSakeRecommendList(String food);
    public void insertSakeRecommend(@Param("food") String food,
                                    @Param("list") List<SakeRerankItem> list);
}

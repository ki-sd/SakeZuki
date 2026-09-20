package com.sakezuki.backend.recommend.mapper;

import com.sakezuki.backend.recommend.dto.RecommendedFoodResponse;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface RecommendMapper {
    public List<RecommendedFoodResponse> getFoodRecommendList(Long sakeNo);
    public void insertFoodRecommend(@Param("sakeNo")Long sakeNo,
                                    @Param("list")List<RecommendedFoodResponse> list);
}

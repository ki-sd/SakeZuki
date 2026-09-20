package com.sakezuki.backend.sake.mapper;

import com.sakezuki.backend.sake.dto.SakeDetailResponse;
import com.sakezuki.backend.sake.dto.SakeListResponse;
import com.sakezuki.backend.sake.dto.SakeSearchResponse;
import org.apache.ibatis.annotations.Mapper;
import java.util.*;

@Mapper
public interface SakeMapper {
    // 사케 리스트
    public List<SakeListResponse> getSakeList(Map<String,Object> map);
    // 사케 총 갯수
    public int sakeListCount(Map<String,Object> map);
    // 사케 상세
    public SakeDetailResponse getSakeDetail(Long no);
    // 사케 검색
    public List<SakeSearchResponse> searchSakeData(String search);
}

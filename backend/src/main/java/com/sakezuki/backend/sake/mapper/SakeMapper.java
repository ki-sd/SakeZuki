package com.sakezuki.backend.sake.mapper;

import com.sakezuki.backend.sake.dto.SakeListResponse;
import org.apache.ibatis.annotations.Mapper;
import java.util.*;

@Mapper
public interface SakeMapper {

    public List<SakeListResponse> getSakeList(Map<String,Object> map);
    public int sakeListCount(Map<String,Object> map);
}

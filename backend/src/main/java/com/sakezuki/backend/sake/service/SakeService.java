package com.sakezuki.backend.sake.service;

import com.sakezuki.backend.sake.dto.SakeDetailResponse;
import com.sakezuki.backend.sake.dto.SakeListResponse;

import java.util.*;

public interface SakeService {
    public List<SakeListResponse> getSakeList(int page,String fd,String sakeType);
    public Map<String,Object> sakeListPage(int page,String fd,String sakeType);
    public SakeDetailResponse getSakeDetail(Long no);
}

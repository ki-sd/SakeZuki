package com.sakezuki.backend.sake.service;

import com.sakezuki.backend.sake.dto.SakeDetailResponse;
import com.sakezuki.backend.sake.dto.SakeListResponse;
import com.sakezuki.backend.sake.mapper.SakeMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.*;

@Service
@RequiredArgsConstructor
public class SakeServiceImpl implements SakeService {
    private final SakeMapper sMapper;
    private final int ROW=12;

    private Map<String,Object> createSearchMap(String fd,String sakeType){
        Map<String,Object> map=new HashMap<>();
        if(fd!=null && !fd.isEmpty()){
            map.put("fd",fd);
        }
        if(sakeType!=null && !sakeType.isEmpty()){
            map.put("sakeType",sakeType);
        }
        return map;
    }

    @Override
    public List<SakeListResponse> getSakeList(int page, String fd, String sakeType) {
        if(page<1) throw new IllegalArgumentException("페이지는 1 이상이어야 합니다.");

        Map<String,Object> map=createSearchMap(fd,sakeType);
        int start=(page*ROW)-ROW;
        map.put("start",start);
        return sMapper.getSakeList(map);
    }

    @Override
    public Map<String, Object> sakeListPage(int page,String fd, String sakeType) {
        if(page<1) throw new IllegalArgumentException("페이지는 1 이상이어야 합니다.");

        Map<String,Object> map=createSearchMap(fd,sakeType);
        int count=sMapper.sakeListCount(map);
        int totalpage=(int)Math.ceil(count/(double)ROW);

        final int BLOCK=10;
        int startPage=((page-1)/BLOCK*BLOCK)+1;
        int endPage=((page-1)/BLOCK*BLOCK)+BLOCK;
        if(endPage>totalpage) endPage=totalpage;

        Map<String,Object> result=new HashMap<>();
        result.put("count",count);
        result.put("curpage",page);
        result.put("totalpage",totalpage);
        result.put("startPage",startPage);
        result.put("endPage",endPage);
        return result;
    }

    @Override
    public SakeDetailResponse getSakeDetail(Long no){
        return sMapper.getSakeDetail(no);
    }
}

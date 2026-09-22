package com.sakezuki.backend.sake.service;

import com.sakezuki.backend.sake.dto.SakeDetailResponse;
import com.sakezuki.backend.sake.dto.SakeListResponse;
import com.sakezuki.backend.sake.dto.SakeSearchResponse;
import com.sakezuki.backend.sake.mapper.SakeMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.*;

@Service
@RequiredArgsConstructor
public class SakeServiceImpl implements SakeService {
    private final SakeMapper sMapper;
    private final int ROW=12;

    // 목록과 COUNT 쿼리에 같은 조건을 전달해 카드 수와 페이지 수가 서로 어긋나지 않게 한다.
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

    // UI가 보낸 1부터 시작하는 페이지를 SQL OFFSET으로 바꾼다. 한 페이지는 12건이다.
    @Override
    public List<SakeListResponse> getSakeList(int page, String fd, String sakeType) {
        if(page<1) throw new IllegalArgumentException("페이지는 1 이상이어야 합니다.");

        Map<String,Object> map=createSearchMap(fd,sakeType);
        int start=(page*ROW)-ROW;
        map.put("start",start);
        return sMapper.getSakeList(map);
    }

    // COUNT를 별도로 조회해 전체 페이지와 한 번에 보여줄 페이지 버튼 범위를 계산한다.
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
        if(no==null){
            throw new IllegalArgumentException("존재하지 않는 사케입니다.");
        }
        return sMapper.getSakeDetail(no);
    }

    @Override
    public List<SakeSearchResponse> searchSakeList(String search){
        if(search==null || search.trim().isBlank()){
            throw new IllegalArgumentException("잘못된 검색어입니다.");
        }
        return sMapper.searchSakeData(search.trim());
    }
}

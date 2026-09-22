package com.sakezuki.backend.sake.controller;

import com.sakezuki.backend.sake.dto.SakeDetailResponse;
import com.sakezuki.backend.sake.dto.SakeListResponse;
import com.sakezuki.backend.sake.dto.SakeSearchResponse;
import com.sakezuki.backend.sake.service.SakeService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/sake")
public class SakeController {
    private final SakeService sService;

    // 프론트의 페이지·검색어·종류를 받아 Service의 목록과 페이지 메타데이터를 한 응답으로 합친다.
    // Controller는 HTTP 경계만 담당하고 검색 SQL과 페이지 계산은 아래 계층에 둔다.
    @GetMapping("/list")
    public ResponseEntity<Map<String,Object>> sakeList(@RequestParam(value="page",defaultValue="1")int page,
                                                       @RequestParam(value="fd",required=false)String fd,
                                                       @RequestParam(value="sakeType",required=false)String sakeType){
        List<SakeListResponse> list=sService.getSakeList(page,fd,sakeType);
        Map<String,Object> map=sService.sakeListPage(page,fd,sakeType);
        map.put("list",list);
        return ResponseEntity.ok(map);
    }

    // 목록 카드의 no가 URL 경로를 거쳐 같은 SAKE 행의 상세 조회로 이어진다.
    @GetMapping("/{no}")
    public ResponseEntity<SakeDetailResponse> sakeDetail(@PathVariable("no") Long no){
        return ResponseEntity.ok(sService.getSakeDetail(no));
    }

    // 추천 자동완성은 전체 목록 대신 간단한 검색 결과를 받아 실제 sakeNo를 선택한다.
    @GetMapping("/search")
    public ResponseEntity<List<SakeSearchResponse>> sakeSearch(@RequestParam("search") String search){
        return ResponseEntity.ok(sService.searchSakeList(search));
    }
}

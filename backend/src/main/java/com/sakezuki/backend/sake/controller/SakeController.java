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

    @GetMapping("/list")
    public ResponseEntity<Map<String,Object>> sakeList(@RequestParam(value="page",defaultValue="1")int page,
                                                       @RequestParam(value="fd",required=false)String fd,
                                                       @RequestParam(value="sakeType",required=false)String sakeType){
        List<SakeListResponse> list=sService.getSakeList(page,fd,sakeType);
        Map<String,Object> map=sService.sakeListPage(page,fd,sakeType);
        map.put("list",list);
        return ResponseEntity.ok(map);
    }

    @GetMapping("/{no}")
    public ResponseEntity<SakeDetailResponse> sakeDetail(@PathVariable("no") Long no){
        return ResponseEntity.ok(sService.getSakeDetail(no));
    }

    @GetMapping("/search")
    public ResponseEntity<List<SakeSearchResponse>> sakeSearch(@RequestParam("search") String search){
        return ResponseEntity.ok(sService.searchSakeList(search));
    }
}

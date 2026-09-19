package com.sakezuki.backend.sake.controller;

import com.sakezuki.backend.sake.dto.SakeListResponse;
import com.sakezuki.backend.sake.service.SakeService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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
}

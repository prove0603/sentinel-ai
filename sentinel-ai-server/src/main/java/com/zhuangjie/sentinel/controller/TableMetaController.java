package com.zhuangjie.sentinel.controller;

import com.zhuangjie.sentinel.common.Result;
import com.zhuangjie.sentinel.service.KnowledgeService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/table-meta")
@RequiredArgsConstructor
public class TableMetaController {

    private final KnowledgeService knowledgeService;

    @PostMapping("/auto-collect/{projectId}")
    public Result<Integer> autoCollect(@PathVariable Long projectId) {
        int count = knowledgeService.autoCollect(projectId);
        return Result.ok(count);
    }

    @GetMapping("/list/{projectName}")
    public Result<List<String>> list(@PathVariable String projectName) {
        return Result.ok(knowledgeService.listTables(projectName));
    }
}

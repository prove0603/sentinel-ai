package com.zhuangjie.sentinel.controller;

import com.zhuangjie.sentinel.common.PageResult;
import com.zhuangjie.sentinel.common.Result;
import com.zhuangjie.sentinel.db.entity.KnowledgeEntry;
import com.zhuangjie.sentinel.rag.KnowledgeRagService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/knowledge")
@RequiredArgsConstructor
public class KnowledgeController {

    private final KnowledgeRagService knowledgeRagService;

    @GetMapping("/page")
    public Result<PageResult<KnowledgeEntry>> page(
            @RequestParam(defaultValue = "1") int current,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String knowledgeType) {
        var page = knowledgeRagService.page(current, size, knowledgeType);
        return Result.ok(PageResult.of(page));
    }

    @GetMapping("/list")
    public Result<List<KnowledgeEntry>> list() {
        return Result.ok(knowledgeRagService.listAll());
    }

    @GetMapping("/{id}")
    public Result<KnowledgeEntry> detail(@PathVariable Long id) {
        return Result.ok(knowledgeRagService.getById(id));
    }

    @PostMapping
    public Result<KnowledgeEntry> create(@RequestBody KnowledgeEntry entry) {
        return Result.ok(knowledgeRagService.create(entry));
    }

    @PutMapping
    public Result<KnowledgeEntry> update(@RequestBody KnowledgeEntry entry) {
        return Result.ok(knowledgeRagService.update(entry));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        knowledgeRagService.delete(id);
        return Result.ok(null);
    }

    @PostMapping("/re-embed")
    public Result<Map<String, Object>> reEmbed(@RequestParam(defaultValue = "false") boolean forceAll) {
        int count = knowledgeRagService.reEmbedAll(forceAll);
        return Result.ok(Map.of("embeddedCount", count, "ragAvailable", knowledgeRagService.isRagAvailable()));
    }

    @GetMapping("/status")
    public Result<Map<String, Object>> status() {
        return Result.ok(Map.of("ragAvailable", knowledgeRagService.isRagAvailable()));
    }
}

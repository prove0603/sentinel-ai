package com.zhuangjie.sentinel.controller;

import com.zhuangjie.sentinel.common.Result;
import com.zhuangjie.sentinel.db.entity.PromptTemplate;
import com.zhuangjie.sentinel.service.PromptTemplateService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Prompt 模板管理接口。
 * 支持在线查看、编辑、启停 AI 分析的系统/用户提示词。
 */
@RestController
@RequestMapping("/api/prompt")
@RequiredArgsConstructor
public class PromptController {

    private final PromptTemplateService promptTemplateService;

    @GetMapping("/list")
    public Result<List<PromptTemplate>> list() {
        return Result.ok(promptTemplateService.listAll());
    }

    @GetMapping("/key/{templateKey}")
    public Result<PromptTemplate> getByKey(@PathVariable String templateKey) {
        PromptTemplate template = promptTemplateService.getByKey(templateKey);
        return template != null ? Result.ok(template) : Result.fail("未找到模板: " + templateKey);
    }

    @PostMapping
    public Result<PromptTemplate> create(@RequestBody PromptTemplate template) {
        PromptTemplate existing = promptTemplateService.getByKey(template.getTemplateKey());
        if (existing != null) {
            return Result.fail("模板 key 已存在: " + template.getTemplateKey());
        }
        return Result.ok(promptTemplateService.create(template));
    }

    @PutMapping("/{id}")
    public Result<PromptTemplate> update(@PathVariable Long id, @RequestBody PromptTemplate template) {
        template.setId(id);
        return Result.ok(promptTemplateService.update(template));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        promptTemplateService.delete(id);
        return Result.ok(null);
    }

    @PutMapping("/{id}/toggle")
    public Result<Void> toggle(@PathVariable Long id) {
        boolean success = promptTemplateService.toggleStatus(id);
        return success ? Result.ok(null) : Result.fail("模板不存在");
    }
}

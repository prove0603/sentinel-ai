package com.zhuangjie.sentinel.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.zhuangjie.sentinel.db.entity.PromptTemplate;
import com.zhuangjie.sentinel.db.service.PromptTemplateDbService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Prompt 模板管理服务。
 * <p>
 * 提供 CRUD 操作和按 templateKey 查询功能。
 * AiSqlAnalyzer 通过 templateKey 加载系统/用户提示词。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PromptTemplateService {

    private final PromptTemplateDbService promptTemplateDbService;

    /**
     * 根据 templateKey 获取启用的模板内容。
     * 找不到则返回 null（调用方应 fallback 到默认值）。
     */
    public String getContent(String templateKey) {
        PromptTemplate template = promptTemplateDbService.getOne(
                new LambdaQueryWrapper<PromptTemplate>()
                        .eq(PromptTemplate::getTemplateKey, templateKey)
                        .eq(PromptTemplate::getStatus, 1));
        return template != null ? template.getContent() : null;
    }

    public PromptTemplate getByKey(String templateKey) {
        return promptTemplateDbService.getOne(
                new LambdaQueryWrapper<PromptTemplate>()
                        .eq(PromptTemplate::getTemplateKey, templateKey));
    }

    public List<PromptTemplate> listAll() {
        return promptTemplateDbService.list(
                new LambdaQueryWrapper<PromptTemplate>()
                        .orderByAsc(PromptTemplate::getTemplateKey));
    }

    public PromptTemplate create(PromptTemplate template) {
        template.setStatus(1);
        template.setCreateTime(LocalDateTime.now());
        template.setUpdateTime(LocalDateTime.now());
        promptTemplateDbService.save(template);
        log.info("Prompt template created: key={}, name={}", template.getTemplateKey(), template.getTemplateName());
        return template;
    }

    public PromptTemplate update(PromptTemplate template) {
        template.setUpdateTime(LocalDateTime.now());
        promptTemplateDbService.updateById(template);
        log.info("Prompt template updated: id={}, key={}", template.getId(), template.getTemplateKey());
        return template;
    }

    public boolean delete(Long id) {
        log.info("Prompt template deleted: id={}", id);
        return promptTemplateDbService.removeById(id);
    }

    public boolean toggleStatus(Long id) {
        PromptTemplate existing = promptTemplateDbService.getById(id);
        if (existing == null) return false;
        PromptTemplate update = new PromptTemplate();
        update.setId(id);
        update.setStatus(existing.getStatus() == 1 ? 0 : 1);
        update.setUpdateTime(LocalDateTime.now());
        promptTemplateDbService.updateById(update);
        log.info("Prompt template toggled: id={}, newStatus={}", id, update.getStatus());
        return true;
    }
}

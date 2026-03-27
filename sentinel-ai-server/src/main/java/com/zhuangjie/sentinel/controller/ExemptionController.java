package com.zhuangjie.sentinel.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.zhuangjie.sentinel.common.PageResult;
import com.zhuangjie.sentinel.common.Result;
import com.zhuangjie.sentinel.db.entity.ExemptionRule;
import com.zhuangjie.sentinel.db.entity.SqlRecord;
import com.zhuangjie.sentinel.db.service.ExemptionRuleDbService;
import com.zhuangjie.sentinel.db.service.SqlRecordDbService;
import com.zhuangjie.sentinel.pojo.dto.ScannedSql;
import com.zhuangjie.sentinel.pojo.enums.SqlSourceType;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;

@RestController
@RequestMapping("/api/exemption")
@RequiredArgsConstructor
public class ExemptionController {

    private final ExemptionRuleDbService exemptionRuleDbService;
    private final SqlRecordDbService sqlRecordDbService;

    @GetMapping("/page")
    public Result<PageResult<ExemptionRule>> page(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String ruleType,
            @RequestParam(required = false) Long projectId) {

        LambdaQueryWrapper<ExemptionRule> wrapper = new LambdaQueryWrapper<ExemptionRule>()
                .eq(ruleType != null, ExemptionRule::getRuleType, ruleType)
                .eq(projectId != null, ExemptionRule::getProjectId, projectId)
                .orderByDesc(ExemptionRule::getCreateTime);

        Page<ExemptionRule> result = exemptionRuleDbService.page(new Page<>(page, size), wrapper);
        return Result.ok(PageResult.of(result));
    }

    @PostMapping
    public Result<ExemptionRule> create(@RequestBody ExemptionRule rule) {
        rule.setStatus(1);
        rule.setCreateTime(LocalDateTime.now());
        rule.setUpdateTime(LocalDateTime.now());
        exemptionRuleDbService.save(rule);
        return Result.ok(rule);
    }

    @PutMapping("/{id}")
    public Result<ExemptionRule> update(@PathVariable Long id, @RequestBody ExemptionRule rule) {
        ExemptionRule existing = exemptionRuleDbService.getById(id);
        if (existing == null) {
            return Result.fail("Rule not found: " + id);
        }
        existing.setRuleType(rule.getRuleType());
        existing.setPattern(rule.getPattern());
        existing.setReason(rule.getReason());
        existing.setProjectId(rule.getProjectId());
        existing.setUpdateTime(LocalDateTime.now());
        exemptionRuleDbService.updateById(existing);
        return Result.ok(existing);
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        exemptionRuleDbService.removeById(id);
        return Result.ok(null);
    }

    @PutMapping("/{id}/toggle")
    public Result<ExemptionRule> toggle(@PathVariable Long id) {
        ExemptionRule rule = exemptionRuleDbService.getById(id);
        if (rule == null) {
            return Result.fail("Rule not found: " + id);
        }
        rule.setStatus(rule.getStatus() == 1 ? 0 : 1);
        rule.setUpdateTime(LocalDateTime.now());
        exemptionRuleDbService.updateById(rule);
        return Result.ok(rule);
    }

    /**
     * Preview: show which existing SQL records would be matched by a given rule.
     * Used for the confirmation dialog before creating/enabling a rule.
     */
    @PostMapping("/preview")
    public Result<Map<String, Object>> preview(@RequestBody ExemptionRule rule,
                                                @RequestParam(required = false) Long projectId) {
        LambdaQueryWrapper<SqlRecord> qw = new LambdaQueryWrapper<SqlRecord>()
                .eq(SqlRecord::getStatus, 1);
        if (projectId != null) {
            qw.eq(SqlRecord::getProjectId, projectId);
        }

        List<SqlRecord> records = sqlRecordDbService.list(qw);
        List<Map<String, Object>> matched = new ArrayList<>();

        for (SqlRecord r : records) {
            ScannedSql scanned = new ScannedSql(
                    r.getSqlText(), r.getSqlNormalized(), r.getSqlType(),
                    SqlSourceType.fromCode(r.getSourceType()),
                    r.getSourceFile(), r.getSourceLocation());

            ExemptionRule tempRule = new ExemptionRule();
            tempRule.setRuleType(rule.getRuleType());
            tempRule.setPattern(rule.getPattern());
            tempRule.setProjectId(rule.getProjectId());
            tempRule.setStatus(1);

            if (matchesSingle(tempRule, scanned)) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", r.getId());
                item.put("sourceLocation", r.getSourceLocation());
                item.put("sqlType", r.getSqlType());
                item.put("sqlNormalized", truncate(r.getSqlNormalized(), 120));
                matched.add(item);
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("matchedCount", matched.size());
        result.put("totalRecords", records.size());
        result.put("matched", matched.size() > 50 ? matched.subList(0, 50) : matched);
        return Result.ok(result);
    }

    private boolean matchesSingle(ExemptionRule rule, ScannedSql sql) {
        return switch (rule.getRuleType()) {
            case "SOURCE_CLASS" -> {
                String loc = sql.sourceLocation();
                String p = rule.getPattern();
                if (loc == null || p == null) yield false;
                if (loc.startsWith(p + ".")) yield true;
                int lastDot = loc.lastIndexOf('.');
                if (lastDot > 0) {
                    String cls = loc.substring(0, lastDot);
                    yield cls.equals(p) || cls.endsWith("." + p);
                }
                yield false;
            }
            case "TABLE_NAME" -> {
                String norm = sql.sqlNormalized();
                if (norm == null) yield false;
                yield norm.toLowerCase().contains(rule.getPattern().toLowerCase());
            }
            default -> false;
        };
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return null;
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}

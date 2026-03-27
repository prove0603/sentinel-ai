package com.zhuangjie.sentinel.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.zhuangjie.sentinel.db.entity.ExemptionRule;
import com.zhuangjie.sentinel.db.service.ExemptionRuleDbService;
import com.zhuangjie.sentinel.knowledge.TableNameExtractor;
import com.zhuangjie.sentinel.pojo.dto.ScannedSql;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class ExemptionService {

    private final ExemptionRuleDbService exemptionRuleDbService;
    @Autowired(required = false)
    private TableNameExtractor tableNameExtractor;

    /**
     * Checks if a scanned SQL matches any active exemption rule.
     *
     * @param sql       the scanned SQL
     * @param projectId the project context (rules with null projectId are global)
     * @return the matched rule, or null if no exemption applies
     */
    public ExemptionRule match(ScannedSql sql, Long projectId) {
        List<ExemptionRule> rules = exemptionRuleDbService.list(
                new LambdaQueryWrapper<ExemptionRule>()
                        .eq(ExemptionRule::getStatus, 1)
                        .and(w -> w.isNull(ExemptionRule::getProjectId)
                                .or().eq(ExemptionRule::getProjectId, projectId))
        );

        for (ExemptionRule rule : rules) {
            if (matches(rule, sql)) {
                return rule;
            }
        }
        return null;
    }

    private boolean matches(ExemptionRule rule, ScannedSql sql) {
        return switch (rule.getRuleType()) {
            case "SOURCE_CLASS" -> matchSourceClass(rule.getPattern(), sql.sourceLocation());
            case "TABLE_NAME" -> matchTableName(rule.getPattern(), sql.sqlNormalized());
            default -> false;
        };
    }

    /**
     * SOURCE_CLASS: exact match on the class part of sourceLocation.
     * sourceLocation formats:
     *   - MapperXML:      "com.xxx.DeleteNotAfiDataManagementMapper.deleteByIds"
     *   - Annotation/QW:  "DeleteNotAfiDataManagementMapper.someMethod"
     * We match if any segment (split by '.') equals the pattern,
     * or the sourceLocation starts with the pattern + ".".
     */
    private boolean matchSourceClass(String pattern, String sourceLocation) {
        if (sourceLocation == null || pattern == null) return false;

        if (sourceLocation.startsWith(pattern + ".")) {
            return true;
        }

        int lastDot = sourceLocation.lastIndexOf('.');
        if (lastDot > 0) {
            String withoutMethod = sourceLocation.substring(0, lastDot);
            if (withoutMethod.equals(pattern) || withoutMethod.endsWith("." + pattern)) {
                return true;
            }
        }
        return false;
    }

    /**
     * TABLE_NAME: checks if any table referenced in the SQL matches the pattern exactly.
     */
    private boolean matchTableName(String pattern, String sqlNormalized) {
        if (tableNameExtractor == null || sqlNormalized == null || pattern == null) return false;
        Set<String> tables = tableNameExtractor.extract(sqlNormalized);
        return tables.contains(pattern.toLowerCase()) || tables.contains(pattern);
    }
}

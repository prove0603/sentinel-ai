package com.zhuangjie.sentinel.mcp;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.zhuangjie.sentinel.db.entity.ProjectConfig;
import com.zhuangjie.sentinel.db.entity.SqlAnalysis;
import com.zhuangjie.sentinel.db.entity.SqlRecord;
import com.zhuangjie.sentinel.db.service.ProjectConfigDbService;
import com.zhuangjie.sentinel.db.service.SqlAnalysisDbService;
import com.zhuangjie.sentinel.db.service.SqlRecordDbService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Sentinel AI MCP Tools — exposes SQL analysis capabilities via MCP protocol.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SqlAnalysisMcpTools {

    private final SqlAnalysisDbService sqlAnalysisDbService;
    private final SqlRecordDbService sqlRecordDbService;
    private final ProjectConfigDbService projectConfigDbService;

    @Tool(description = "获取所有被标记为有风险的SQL列表，支持按风险等级(P0最严重到P4最轻)和处理状态(PENDING/CONFIRMED/FIXED/IGNORED)过滤。" +
            "返回SQL的风险等级、AI分析结论、来源文件等信息。适合用于了解当前项目中有哪些SQL存在性能问题。")
    public List<Map<String, Object>> listRiskySql(
            @ToolParam(description = "风险等级过滤，可选值: P0(致命), P1(严重), P2(较大风险), P3(中等), P4(低风险)。不传则返回所有等级") String riskLevel,
            @ToolParam(description = "处理状态过滤，可选值: PENDING(待处理), CONFIRMED(已确认), FIXED(已修复), IGNORED(已忽略), ANALYZED(已分析)。不传则返回所有状态") String handleStatus,
            @ToolParam(description = "返回条数限制，默认20，最大100") Integer limit) {

        int effectiveLimit = (limit == null || limit <= 0) ? 20 : Math.min(limit, 100);

        LambdaQueryWrapper<SqlAnalysis> wrapper = new LambdaQueryWrapper<>();
        if (riskLevel != null && !riskLevel.isBlank()) {
            wrapper.eq(SqlAnalysis::getFinalRiskLevel, riskLevel.trim().toUpperCase());
        }
        if (handleStatus != null && !handleStatus.isBlank()) {
            wrapper.eq(SqlAnalysis::getHandleStatus, handleStatus.trim().toUpperCase());
        }
        wrapper.orderByAsc(SqlAnalysis::getFinalRiskLevel)
                .orderByDesc(SqlAnalysis::getCreateTime);

        Page<SqlAnalysis> page = sqlAnalysisDbService.page(new Page<>(1, effectiveLimit), wrapper);

        List<Map<String, Object>> results = new ArrayList<>();
        for (SqlAnalysis analysis : page.getRecords()) {
            Map<String, Object> item = new HashMap<>();
            item.put("analysisId", analysis.getId());
            item.put("riskLevel", analysis.getFinalRiskLevel());
            item.put("handleStatus", analysis.getHandleStatus());
            item.put("aiAnalysis", analysis.getAiAnalysis());
            item.put("aiModel", analysis.getAiModel());

            SqlRecord record = sqlRecordDbService.getById(analysis.getSqlRecordId());
            if (record != null) {
                item.put("sqlText", truncate(record.getSqlText(), 500));
                item.put("sqlType", record.getSqlType());
                item.put("sourceFile", record.getSourceFile());
                item.put("sourceLocation", record.getSourceLocation());
                item.put("sourceType", record.getSourceType());

                ProjectConfig project = projectConfigDbService.getById(record.getProjectId());
                if (project != null) {
                    item.put("projectName", project.getProjectName());
                }
            }

            if (analysis.getAiIndexSuggestion() != null) {
                item.put("indexSuggestion", analysis.getAiIndexSuggestion());
            }
            if (analysis.getAiRewriteSuggestion() != null) {
                item.put("rewriteSuggestion", analysis.getAiRewriteSuggestion());
            }
            if (analysis.getAiEstimatedScanRows() != null) {
                item.put("estimatedScanRows", analysis.getAiEstimatedScanRows());
            }

            results.add(item);
        }
        return results;
    }

    @Tool(description = "获取某条SQL分析的完整详情，包含原始SQL文本、AI分析结论、索引建议、重写建议、预估扫描行数等。" +
            "需要传入analysisId（可以先通过listRiskySql获取）。")
    public Map<String, Object> getSqlAnalysisDetail(
            @ToolParam(description = "SQL分析记录的ID") Long analysisId) {

        SqlAnalysis analysis = sqlAnalysisDbService.getById(analysisId);
        if (analysis == null) {
            return Map.of("error", "未找到分析记录: " + analysisId);
        }

        Map<String, Object> detail = new HashMap<>();
        detail.put("analysisId", analysis.getId());
        detail.put("riskLevel", analysis.getFinalRiskLevel());
        detail.put("handleStatus", analysis.getHandleStatus());
        detail.put("aiRiskLevel", analysis.getAiRiskLevel());
        detail.put("aiAnalysis", analysis.getAiAnalysis());
        detail.put("aiIndexSuggestion", analysis.getAiIndexSuggestion());
        detail.put("aiRewriteSuggestion", analysis.getAiRewriteSuggestion());
        detail.put("aiEstimatedScanRows", analysis.getAiEstimatedScanRows());
        detail.put("aiEstimatedExecTimeMs", analysis.getAiEstimatedExecTimeMs());
        detail.put("aiModel", analysis.getAiModel());
        detail.put("aiTokensUsed", analysis.getAiTokensUsed());
        detail.put("handleNote", analysis.getHandleNote());
        detail.put("createTime", analysis.getCreateTime() != null ? analysis.getCreateTime().toString() : null);

        SqlRecord record = sqlRecordDbService.getById(analysis.getSqlRecordId());
        if (record != null) {
            detail.put("sqlText", record.getSqlText());
            detail.put("sqlNormalized", record.getSqlNormalized());
            detail.put("sqlType", record.getSqlType());
            detail.put("sourceType", record.getSourceType());
            detail.put("sourceFile", record.getSourceFile());
            detail.put("sourceLocation", record.getSourceLocation());

            ProjectConfig project = projectConfigDbService.getById(record.getProjectId());
            if (project != null) {
                detail.put("projectName", project.getProjectName());
            }
        }

        return detail;
    }

    @Tool(description = "获取项目整体的SQL风险概览统计，包含各风险等级的SQL数量、各处理状态的数量、项目总SQL数等。" +
            "适合用于快速了解项目SQL健康状况。")
    public Map<String, Object> getSqlRiskOverview() {
        Map<String, Object> overview = new HashMap<>();

        long totalAnalyses = sqlAnalysisDbService.count();
        long totalSqlRecords = sqlRecordDbService.count(
                new LambdaQueryWrapper<SqlRecord>().eq(SqlRecord::getStatus, 1));

        overview.put("totalSqlRecords", totalSqlRecords);
        overview.put("totalAnalyses", totalAnalyses);

        for (String level : List.of("P0", "P1", "P2", "P3", "P4")) {
            long count = sqlAnalysisDbService.count(
                    new LambdaQueryWrapper<SqlAnalysis>().eq(SqlAnalysis::getFinalRiskLevel, level));
            overview.put("risk_" + level, count);
        }

        for (String status : List.of("PENDING", "ANALYZED", "CONFIRMED", "FIXED", "IGNORED")) {
            long count = sqlAnalysisDbService.count(
                    new LambdaQueryWrapper<SqlAnalysis>().eq(SqlAnalysis::getHandleStatus, status));
            overview.put("status_" + status, count);
        }

        List<ProjectConfig> projects = projectConfigDbService.list(
                new LambdaQueryWrapper<ProjectConfig>().eq(ProjectConfig::getStatus, 1));
        overview.put("activeProjects", projects.stream()
                .map(p -> Map.of("id", p.getId(), "name", p.getProjectName()))
                .toList());

        return overview;
    }

    @Tool(description = "列出所有被监控的项目信息，包含项目名称、Git仓库地址、最近扫描时间、扫描的SQL数量等。")
    public List<Map<String, Object>> listProjects() {
        List<ProjectConfig> projects = projectConfigDbService.list(
                new LambdaQueryWrapper<ProjectConfig>().eq(ProjectConfig::getStatus, 1));

        List<Map<String, Object>> result = new ArrayList<>();
        for (ProjectConfig p : projects) {
            Map<String, Object> item = new HashMap<>();
            item.put("projectId", p.getId());
            item.put("projectName", p.getProjectName());
            item.put("gitRemoteUrl", p.getGitRemoteUrl());
            item.put("gitBranch", p.getGitBranch());
            item.put("lastScanCommit", p.getLastScanCommit());
            item.put("lastScanTime", p.getLastScanTime() != null ? p.getLastScanTime().toString() : null);

            long sqlCount = sqlRecordDbService.count(
                    new LambdaQueryWrapper<SqlRecord>()
                            .eq(SqlRecord::getProjectId, p.getId())
                            .eq(SqlRecord::getStatus, 1));
            item.put("activeSqlCount", sqlCount);

            result.add(item);
        }
        return result;
    }

    @Tool(description = "根据关键词搜索SQL记录，可以搜索SQL文本内容、源文件路径、来源位置等。" +
            "适合用于查找特定表或特定条件的SQL是否存在性能风险。")
    public List<Map<String, Object>> searchSqlRecords(
            @ToolParam(description = "搜索关键词，会在SQL文本、源文件路径、来源位置中模糊匹配") String keyword,
            @ToolParam(description = "返回条数限制，默认10，最大50") Integer limit) {

        if (keyword == null || keyword.isBlank()) {
            return List.of(Map.of("error", "搜索关键词不能为空"));
        }

        int effectiveLimit = (limit == null || limit <= 0) ? 10 : Math.min(limit, 50);
        String likeKeyword = "%" + keyword.trim() + "%";

        LambdaQueryWrapper<SqlRecord> wrapper = new LambdaQueryWrapper<SqlRecord>()
                .eq(SqlRecord::getStatus, 1)
                .and(w -> w
                        .like(SqlRecord::getSqlText, likeKeyword)
                        .or().like(SqlRecord::getSourceFile, likeKeyword)
                        .or().like(SqlRecord::getSourceLocation, likeKeyword))
                .orderByDesc(SqlRecord::getUpdateTime);

        Page<SqlRecord> page = sqlRecordDbService.page(new Page<>(1, effectiveLimit), wrapper);

        List<Map<String, Object>> results = new ArrayList<>();
        for (SqlRecord record : page.getRecords()) {
            Map<String, Object> item = new HashMap<>();
            item.put("recordId", record.getId());
            item.put("sqlText", truncate(record.getSqlText(), 300));
            item.put("sqlType", record.getSqlType());
            item.put("sourceType", record.getSourceType());
            item.put("sourceFile", record.getSourceFile());
            item.put("sourceLocation", record.getSourceLocation());

            SqlAnalysis analysis = sqlAnalysisDbService.getOne(
                    new LambdaQueryWrapper<SqlAnalysis>()
                            .eq(SqlAnalysis::getSqlRecordId, record.getId())
                            .orderByDesc(SqlAnalysis::getCreateTime)
                            .last("LIMIT 1"));
            if (analysis != null) {
                item.put("riskLevel", analysis.getFinalRiskLevel());
                item.put("handleStatus", analysis.getHandleStatus());
                item.put("briefAnalysis", truncate(analysis.getAiAnalysis(), 200));
            } else {
                item.put("riskLevel", "未分析");
            }

            results.add(item);
        }
        return results;
    }

    private String truncate(String text, int maxLen) {
        if (text == null) return null;
        return text.length() > maxLen ? text.substring(0, maxLen) + "..." : text;
    }
}

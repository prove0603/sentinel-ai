package com.zhuangjie.sentinel.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.zhuangjie.sentinel.common.PageResult;
import com.zhuangjie.sentinel.common.Result;
import com.zhuangjie.sentinel.db.entity.ProjectConfig;
import com.zhuangjie.sentinel.db.entity.SqlAnalysis;
import com.zhuangjie.sentinel.db.entity.SqlRecord;

import java.util.List;
import com.zhuangjie.sentinel.db.service.SqlAnalysisDbService;
import com.zhuangjie.sentinel.db.service.SqlRecordDbService;
import com.zhuangjie.sentinel.notification.WeComNotificationService;
import com.zhuangjie.sentinel.service.ProjectService;
import com.zhuangjie.sentinel.service.ScanService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/sql-record")
@RequiredArgsConstructor
public class SqlRecordController {

    private final SqlRecordDbService sqlRecordDbService;
    private final SqlAnalysisDbService sqlAnalysisDbService;
    private final ProjectService projectService;
    private final ScanService scanService;

    @Autowired(required = false)
    private WeComNotificationService weComNotificationService;

    @GetMapping("/{id}")
    public Result<SqlRecord> getById(@PathVariable Long id) {
        SqlRecord record = sqlRecordDbService.getById(id);
        if (record == null) {
            return Result.fail("SQL 记录不存在: " + id);
        }
        return Result.ok(record);
    }

    @GetMapping("/page")
    public Result<PageResult<SqlRecord>> page(
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) String sqlType,
            @RequestParam(required = false) String sourceType,
            @RequestParam(required = false) String owner,
            @RequestParam(defaultValue = "1") int current,
            @RequestParam(defaultValue = "20") int size) {

        LambdaQueryWrapper<SqlRecord> wrapper = new LambdaQueryWrapper<SqlRecord>()
                .eq(projectId != null, SqlRecord::getProjectId, projectId)
                .eq(sqlType != null && !sqlType.isBlank(), SqlRecord::getSqlType, sqlType)
                .eq(sourceType != null && !sourceType.isBlank(), SqlRecord::getSourceType, sourceType)
                .like(owner != null && !owner.isBlank(), SqlRecord::getOwner, owner)
                .eq(SqlRecord::getStatus, 1)
                .orderByDesc(SqlRecord::getUpdateTime);

        Page<SqlRecord> page = sqlRecordDbService.page(new Page<>(current, size), wrapper);
        return Result.ok(PageResult.of(page));
    }

    /**
     * 提醒开发人员处理风险 SQL，通过企业微信群 @负责人。
     */
    @PostMapping("/{id}/remind")
    public Result<String> remindOwner(@PathVariable Long id) {
        if (weComNotificationService == null) {
            return Result.fail("企业微信通知未启用，请检查配置");
        }

        SqlRecord record = sqlRecordDbService.getById(id);
        if (record == null) {
            return Result.fail("SQL 记录不存在: " + id);
        }

        String riskLevel = "未分析";
        SqlAnalysis analysis = sqlAnalysisDbService.getOne(
                new LambdaQueryWrapper<SqlAnalysis>()
                        .eq(SqlAnalysis::getSqlRecordId, id)
                        .orderByDesc(SqlAnalysis::getCreateTime)
                        .last("LIMIT 1"));
        if (analysis != null && analysis.getFinalRiskLevel() != null) {
            riskLevel = analysis.getFinalRiskLevel();
        }

        String projectName = "未知项目";
        ProjectConfig project = projectService.getById(record.getProjectId());
        if (project != null) {
            projectName = project.getProjectName();
        }

        weComNotificationService.sendRemindNotification(
                record.getOwner(),
                riskLevel,
                record.getSqlText(),
                record.getSourceLocation(),
                projectName,
                record.getId()
        );

        return Result.ok("已发送提醒通知给 " + record.getOwner());
    }

    /**
     * 工具接口：扫描项目 Git 仓库，为 owner 为空的 SQL 记录填充负责人。
     * projectId 为空时处理所有项目。
     */
    @PostMapping("/fill-owners")
    public Result<String> fillOwners(@RequestParam(required = false) Long projectId) {
        try {
            int updated;
            if (projectId != null) {
                updated = scanService.fillMissingOwners(projectId);
            } else {
                updated = scanService.fillAllMissingOwners();
            }
            return Result.ok("已更新 " + updated + " 条记录的负责人");
        } catch (Exception e) {
            return Result.fail("填充负责人失败: " + e.getMessage());
        }
    }

    /**
     * 工具接口：清理重复和无效的 SQL 记录。
     * 去重（同 project + sql_hash 保留最新）+ 删除含 unknown 表名的记录。
     */
    @PostMapping("/cleanup")
    public Result<String> cleanup() {
        int unknownRemoved = 0;
        int duplicatesRemoved = 0;

        List<SqlRecord> allActive = sqlRecordDbService.list(
                new LambdaQueryWrapper<SqlRecord>().eq(SqlRecord::getStatus, 1));

        for (SqlRecord r : allActive) {
            if (r.getSqlText() != null && r.getSqlText().contains("_unknown")) {
                r.setStatus(0);
                sqlRecordDbService.updateById(r);
                unknownRemoved++;
            }
        }

        allActive = sqlRecordDbService.list(
                new LambdaQueryWrapper<SqlRecord>().eq(SqlRecord::getStatus, 1));

        java.util.Map<String, java.util.List<SqlRecord>> grouped = allActive.stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        r -> r.getProjectId() + ":" + r.getSqlHash()));

        for (var entry : grouped.entrySet()) {
            java.util.List<SqlRecord> group = entry.getValue();
            if (group.size() <= 1) continue;
            group.sort((a, b) -> Long.compare(b.getId(), a.getId()));
            for (int i = 1; i < group.size(); i++) {
                group.get(i).setStatus(0);
                sqlRecordDbService.updateById(group.get(i));
                duplicatesRemoved++;
            }
        }

        return Result.ok("清理完成: 移除 " + unknownRemoved + " 条无效记录, " + duplicatesRemoved + " 条重复记录");
    }
}

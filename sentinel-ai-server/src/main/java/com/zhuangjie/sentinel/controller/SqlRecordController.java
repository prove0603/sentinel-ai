package com.zhuangjie.sentinel.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.zhuangjie.sentinel.common.PageResult;
import com.zhuangjie.sentinel.common.Result;
import com.zhuangjie.sentinel.db.entity.SqlRecord;
import com.zhuangjie.sentinel.db.service.SqlRecordDbService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/sql-record")
@RequiredArgsConstructor
public class SqlRecordController {

    private final SqlRecordDbService sqlRecordDbService;

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
            @RequestParam(defaultValue = "1") int current,
            @RequestParam(defaultValue = "20") int size) {

        LambdaQueryWrapper<SqlRecord> wrapper = new LambdaQueryWrapper<SqlRecord>()
                .eq(projectId != null, SqlRecord::getProjectId, projectId)
                .eq(sqlType != null && !sqlType.isBlank(), SqlRecord::getSqlType, sqlType)
                .eq(sourceType != null && !sourceType.isBlank(), SqlRecord::getSourceType, sourceType)
                .eq(SqlRecord::getStatus, 1)
                .orderByDesc(SqlRecord::getUpdateTime);

        Page<SqlRecord> page = sqlRecordDbService.page(new Page<>(current, size), wrapper);
        return Result.ok(PageResult.of(page));
    }
}

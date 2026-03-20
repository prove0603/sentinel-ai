package com.zhuangjie.sentinel.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.zhuangjie.sentinel.common.Result;
import com.zhuangjie.sentinel.db.entity.ProjectConfig;
import com.zhuangjie.sentinel.db.entity.SqlRecord;
import com.zhuangjie.sentinel.db.service.SqlRecordDbService;
import com.zhuangjie.sentinel.delta.GitRepoManager;
import com.zhuangjie.sentinel.pojo.dto.ScannedSql;
import com.zhuangjie.sentinel.scanner.QueryWrapperScanner;
import com.zhuangjie.sentinel.scanner.SqlNormalizer;
import com.zhuangjie.sentinel.service.ProjectService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.*;

@Slf4j
@RestController
@RequestMapping("/api/tool")
@RequiredArgsConstructor
public class ToolController {

    private final QueryWrapperScanner queryWrapperScanner;
    private final JdbcTemplate jdbcTemplate;
    private final ProjectService projectService;
    private final GitRepoManager gitRepoManager;
    private final SqlRecordDbService sqlRecordDbService;

    @PostMapping("/scan-wrapper")
    public Result<List<ScannedSql>> scanWrapper(@RequestBody String javaCode) {
        List<ScannedSql> results = queryWrapperScanner.scanCodeSnippet(javaCode);
        return Result.ok(results);
    }

    /**
     * Scans all QueryWrapper/LambdaQueryWrapper SQL in a project and saves to sql_record table.
     * Does NOT invoke AI analysis — purely for testing scanner accuracy.
     */
    @PostMapping("/scan-wrapper-project")
    public Result<Map<String, Object>> scanWrapperProject(@RequestParam Long projectId) {
        ProjectConfig project = projectService.getById(projectId);
        if (project == null) {
            return Result.fail("Project not found: " + projectId);
        }

        Path projectRoot;
        try {
            projectRoot = gitRepoManager.syncRepo(project);
        } catch (Exception e) {
            log.error("Failed to sync repo for project {}: {}", project.getProjectName(), e.getMessage());
            return Result.fail("Git sync failed: " + e.getMessage());
        }

        List<ScannedSql> scannedSqls = queryWrapperScanner.scan(projectRoot);

        int saved = 0;
        int skippedDuplicate = 0;
        Set<String> seenHashes = new HashSet<>();

        for (ScannedSql scannedSql : scannedSqls) {
            String sqlHash = SqlNormalizer.hash(scannedSql.sqlNormalized());
            if (sqlHash.isBlank() || !seenHashes.add(sqlHash)) {
                skippedDuplicate++;
                continue;
            }

            boolean exists = sqlRecordDbService.count(
                    new LambdaQueryWrapper<SqlRecord>()
                            .eq(SqlRecord::getProjectId, projectId)
                            .eq(SqlRecord::getSqlHash, sqlHash)
                            .eq(SqlRecord::getStatus, 1)) > 0;
            if (exists) {
                skippedDuplicate++;
                continue;
            }

            SqlRecord record = new SqlRecord();
            record.setProjectId(projectId);
            record.setSqlHash(sqlHash);
            record.setSqlText(scannedSql.sql());
            record.setSqlNormalized(scannedSql.sqlNormalized());
            record.setSqlType(scannedSql.sqlType());
            record.setSourceType(scannedSql.sourceType().getCode());
            record.setSourceFile(scannedSql.sourceFile());
            record.setSourceLocation(scannedSql.sourceLocation());
            record.setStatus(1);
            record.setCreateTime(LocalDateTime.now());
            record.setUpdateTime(LocalDateTime.now());
            sqlRecordDbService.save(record);
            saved++;
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("projectName", project.getProjectName());
        result.put("totalScanned", scannedSqls.size());
        result.put("saved", saved);
        result.put("skippedDuplicate", skippedDuplicate);
        return Result.ok(result);
    }

    @PostMapping("/execute-ddl")
    public Result<String> executeDdl(@RequestBody String sql) {
        String[] statements = sql.split(";");
        int count = 0;
        for (String stmt : statements) {
            String trimmed = stmt.trim();
            if (!trimmed.isEmpty()) {
                jdbcTemplate.execute(trimmed);
                count++;
            }
        }
        return Result.ok("Executed " + count + " statement(s)");
    }
}

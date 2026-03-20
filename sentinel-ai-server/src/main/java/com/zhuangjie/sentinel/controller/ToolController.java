package com.zhuangjie.sentinel.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.zhuangjie.sentinel.analyzer.ModelRouter;
import com.zhuangjie.sentinel.analyzer.provider.ModelProvider.ModelResponse;
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
    private final ModelRouter modelRouter;

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

    /**
     * Tests the ModelRouter round-robin rotation.
     * Calls each provider in sequence with a simple prompt, returning which model responded.
     *
     * @param rounds number of round-robin calls to make (default 3)
     */
    @GetMapping("/test-model-rotation")
    public Result<Map<String, Object>> testModelRotation(
            @RequestParam(defaultValue = "3") int rounds) {

        if (modelRouter == null) {
            return Result.fail("ModelRouter not configured. Set sentinel.ai.enabled=true and configure providers.");
        }

        List<Map<String, Object>> results = new ArrayList<>();
        int success = 0;
        int failed = 0;

        for (int i = 0; i < rounds; i++) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("round", i + 1);
            item.put("expectedModel", modelRouter.peekNext().modelName());

            long start = System.currentTimeMillis();
            try {
                ModelResponse response = modelRouter.call(
                        "You are a helpful assistant.",
                        "Reply with only the model name you are running as, nothing else.");
                long elapsed = System.currentTimeMillis() - start;

                item.put("status", "OK");
                item.put("respondedModel", response.model());
                item.put("content", response.content().substring(0, Math.min(200, response.content().length())));
                item.put("tokensUsed", response.tokensUsed());
                item.put("elapsedMs", elapsed);
                success++;
            } catch (Exception e) {
                long elapsed = System.currentTimeMillis() - start;
                item.put("status", "FAILED");
                item.put("error", e.getMessage());
                item.put("elapsedMs", elapsed);
                failed++;
            }
            results.add(item);
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("totalProviders", modelRouter.getProviders().size());
        summary.put("providerList", modelRouter.getProviders().stream()
                .map(p -> p.name() + " (" + p.modelName() + ")").toList());
        summary.put("totalCalls", rounds);
        summary.put("success", success);
        summary.put("failed", failed);
        summary.put("details", results);
        return Result.ok(summary);
    }
}

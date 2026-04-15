package com.zhuangjie.sentinel.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.zhuangjie.sentinel.analyzer.MultiModelChatService;
import com.zhuangjie.sentinel.analyzer.MultiModelChatService.NamedChatClient;
import com.zhuangjie.sentinel.analyzer.MultiModelChatService.RawCallResult;
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
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import com.zhuangjie.sentinel.pojo.dto.SqlRiskAssessment;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.converter.BeanOutputConverter;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 工具/测试接口控制器。
 * <p>
 * 提供开发调试用的工具接口，包括 QueryWrapper 扫描测试、DDL 执行、多模型轮询测试等。
 * 这些接口主要用于开发和验证阶段，生产环境可按需关闭。
 */
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
    private final ObjectProvider<MultiModelChatService> chatServiceProvider;

    /** 【测试用】扫描单段 Java 代码中的 QueryWrapper SQL */
    @PostMapping("/scan-wrapper")
    public Result<List<ScannedSql>> scanWrapper(@RequestBody String javaCode) {
        List<ScannedSql> results = queryWrapperScanner.scanCodeSnippet(javaCode);
        return Result.ok(results);
    }

    /**
     * 【测试用】扫描项目所有 QueryWrapper SQL 并存入 sql_record 表。
     * 不调用 AI 分析，纯粹用于测试扫描器的准确度。
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

    /** 【测试用】执行 DDL SQL 语句（开发调试用） */
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
     * 【测试用】测试多模型轮询。
     * 依次调用各 provider，返回每个模型的响应结果和耗时，用于验证模型可用性。
     *
     * @param rounds 轮询调用次数（默认 3）
     */
    @GetMapping("/test-model-rotation")
    public Result<Map<String, Object>> testModelRotation(
            @RequestParam(defaultValue = "3") int rounds) {

        MultiModelChatService chatService = chatServiceProvider.getIfAvailable();
        if (chatService == null) {
            return Result.fail("MultiModelChatService not configured. Set sentinel.ai.enabled=true and configure providers.");
        }

        List<Map<String, Object>> results = new ArrayList<>();
        int success = 0;
        int failed = 0;

        for (int i = 0; i < rounds; i++) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("round", i + 1);
            item.put("expectedModel", chatService.peekNext().model());

            long start = System.currentTimeMillis();
            try {
                RawCallResult response = chatService.callRaw(
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
        summary.put("totalProviders", chatService.getClients().size());
        summary.put("providerList", chatService.getClients().stream()
                .map(c -> c.name() + " (" + c.model() + ")").toList());
        summary.put("totalCalls", rounds);
        summary.put("success", success);
        summary.put("failed", failed);
        summary.put("details", results);
        return Result.ok(summary);
    }

    /**
     * 【测试用】逐个模型测试结构化输出（SqlRiskAssessment）的 explanation 字段完整性。
     * 用同一条 SQL 分别调用每个模型，检查返回的 explanation 是否为空。
     */
    @GetMapping("/test-model-structured-output")
    public Result<Map<String, Object>> testModelStructuredOutput() {
        MultiModelChatService chatService = chatServiceProvider.getIfAvailable();
        if (chatService == null) {
            return Result.fail("MultiModelChatService not configured");
        }

        String systemPrompt = "你是一个资深的 MySQL DBA。分析 SQL 语句的性能风险，使用中文回复。";
        String testSql = "SELECT * FROM t_user WHERE name LIKE '%test%'";

        BeanOutputConverter<SqlRiskAssessment> converter = new BeanOutputConverter<>(SqlRiskAssessment.class);
        String userPrompt = "请对以下 SQL 语句进行性能风险分析。\n\nSQL: " + testSql + "\n\n" + converter.getFormat();

        List<Map<String, Object>> results = new ArrayList<>();

        for (NamedChatClient client : chatService.getClients()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("model", client.model());
            item.put("name", client.name());

            long start = System.currentTimeMillis();
            try {
                ChatResponse response = client.chatClient().prompt()
                        .system(systemPrompt)
                        .user(userPrompt)
                        .call()
                        .chatResponse();

                String content = response.getResult().getOutput().getText();
                String cleanJson = content
                        .replaceAll("(?s)```json\\s*", "")
                        .replaceAll("(?s)```\\s*", "")
                        .trim();

                SqlRiskAssessment assessment = converter.convert(cleanJson);
                long elapsed = System.currentTimeMillis() - start;

                item.put("status", "OK");
                item.put("elapsedMs", elapsed);
                item.put("riskLevel", assessment.riskLevel());
                item.put("explanation", assessment.explanation());
                item.put("explanationEmpty", assessment.explanation() == null || assessment.explanation().isBlank());
                item.put("issuesCount", assessment.issues() != null ? assessment.issues().size() : 0);
                item.put("indexSuggestionsCount", assessment.indexSuggestions() != null ? assessment.indexSuggestions().size() : 0);
                item.put("rewriteSuggestionsCount", assessment.rewriteSuggestions() != null ? assessment.rewriteSuggestions().size() : 0);
                item.put("rawJsonPreview", cleanJson.substring(0, Math.min(300, cleanJson.length())));
            } catch (Exception e) {
                long elapsed = System.currentTimeMillis() - start;
                item.put("status", "FAILED");
                item.put("elapsedMs", elapsed);
                item.put("error", e.getMessage());
            }
            results.add(item);
        }

        long emptyCount = results.stream()
                .filter(r -> Boolean.TRUE.equals(r.get("explanationEmpty")))
                .count();

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("testSql", testSql);
        summary.put("totalModels", chatService.getClients().size());
        summary.put("emptyExplanationCount", emptyCount);
        summary.put("conclusion", emptyCount > 0
                ? "WARNING: " + emptyCount + " model(s) returned empty explanation!"
                : "All models returned valid explanation");
        summary.put("details", results);
        return Result.ok(summary);
    }
}

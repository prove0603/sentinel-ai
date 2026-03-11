package com.zhuangjie.sentinel.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.zhuangjie.sentinel.analyzer.AiSqlAnalyzer;
import com.zhuangjie.sentinel.analyzer.AiSqlAnalyzer.AiAnalysisDetail;
import com.zhuangjie.sentinel.analyzer.AnalysisResult;
import com.zhuangjie.sentinel.analyzer.RuleBasedAnalyzer;
import com.zhuangjie.sentinel.analyzer.rules.RuleViolation;
import com.zhuangjie.sentinel.db.entity.ProjectConfig;
import com.zhuangjie.sentinel.db.entity.ScanBatch;
import com.zhuangjie.sentinel.db.entity.SqlAnalysis;
import com.zhuangjie.sentinel.db.entity.SqlRecord;
import com.zhuangjie.sentinel.db.service.ProjectConfigDbService;
import com.zhuangjie.sentinel.db.service.ScanBatchDbService;
import com.zhuangjie.sentinel.db.service.SqlAnalysisDbService;
import com.zhuangjie.sentinel.db.service.SqlRecordDbService;
import com.zhuangjie.sentinel.delta.ComparisonResult;
import com.zhuangjie.sentinel.delta.DeltaResult;
import com.zhuangjie.sentinel.delta.GitDeltaDetector;
import com.zhuangjie.sentinel.delta.SqlHashComparator;
import com.zhuangjie.sentinel.pojo.dto.ScannedSql;
import com.zhuangjie.sentinel.pojo.dto.SqlRiskAssessment;
import com.zhuangjie.sentinel.pojo.enums.RiskLevel;
import com.zhuangjie.sentinel.pojo.enums.ScanType;
import com.zhuangjie.sentinel.scanner.MapperXmlScanner;
import com.zhuangjie.sentinel.scanner.SqlNormalizer;
import cn.hutool.json.JSONUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

@Slf4j
@Service
@RequiredArgsConstructor
public class ScanService {

    private final ProjectService projectService;
    private final ProjectConfigDbService projectConfigDbService;
    private final ScanBatchDbService scanBatchDbService;
    private final SqlRecordDbService sqlRecordDbService;
    private final SqlAnalysisDbService sqlAnalysisDbService;
    private final MapperXmlScanner mapperXmlScanner;
    private final RuleBasedAnalyzer ruleBasedAnalyzer;
    private final AiSqlAnalyzer aiSqlAnalyzer;
    private final GitDeltaDetector gitDeltaDetector;
    private final SqlHashComparator sqlHashComparator;

    @Value("${sentinel.ai.max-ai-calls-per-scan:-1}")
    private int maxAiCallsPerScan;

    @Async
    public CompletableFuture<ScanBatch> triggerScan(Long projectId, boolean forceFullScan) {
        ProjectConfig project = projectService.getById(projectId);
        if (project == null) {
            throw new IllegalArgumentException("Project not found: " + projectId);
        }

        Path projectRoot = Path.of(project.getGitRepoPath());
        String headCommit = gitDeltaDetector.resolveHead(projectRoot);

        boolean isFullScan = forceFullScan
                || project.getLastScanCommit() == null
                || headCommit == null;

        ScanType scanType = isFullScan ? ScanType.FULL : ScanType.INCREMENTAL;

        ScanBatch batch = new ScanBatch();
        batch.setProjectId(projectId);
        batch.setScanType(scanType.getCode());
        batch.setStatus("RUNNING");
        batch.setToCommit(headCommit);
        scanBatchDbService.save(batch);

        long startTime = System.currentTimeMillis();

        try {
            log.info("Scan started: projectId={}, type={}, batchId={}, aiEnabled={}, maxAiCalls={}",
                    projectId, scanType, batch.getId(), aiSqlAnalyzer.isAvailable(),
                    maxAiCallsPerScan < 0 ? "unlimited" : maxAiCallsPerScan);

            if (isFullScan) {
                doFullScan(batch, project, projectRoot);
            } else {
                doIncrementalScan(batch, project, projectRoot, headCommit);
            }

            batch.setStatus("COMPLETED");

            if (headCommit != null) {
                project.setLastScanCommit(headCommit);
                project.setLastScanTime(LocalDateTime.now());
                projectConfigDbService.updateById(project);
            }
        } catch (Exception e) {
            log.error("Scan failed: projectId={}, batchId={}", projectId, batch.getId(), e);
            batch.setStatus("FAILED");
        } finally {
            batch.setScanDurationMs(System.currentTimeMillis() - startTime);
            scanBatchDbService.updateById(batch);
        }

        return CompletableFuture.completedFuture(batch);
    }

    // ==================== Full Scan ====================

    private void doFullScan(ScanBatch batch, ProjectConfig project, Path projectRoot) {
        List<ScannedSql> scannedSqls = mapperXmlScanner.scan(projectRoot);

        int riskCount = 0;
        int aiAnalyzedCount = 0;
        Set<String> seenHashes = new HashSet<>();

        for (ScannedSql scannedSql : scannedSqls) {
            String sqlHash = SqlNormalizer.hash(scannedSql.sqlNormalized());
            if (sqlHash.isBlank() || !seenHashes.add(sqlHash)) {
                continue;
            }

            SqlRecord record = saveSqlRecord(scannedSql, sqlHash, project.getId(), batch.getId());
            AnalysisResult ruleResult = ruleBasedAnalyzer.analyze(scannedSql);

            if (!ruleResult.violations().isEmpty()) {
                SqlAnalysis analysis = saveRuleAnalysisResult(record, batch.getId(), ruleResult);

                boolean isHighRisk = ruleResult.riskLevel().ordinal() <= RiskLevel.P2.ordinal();
                if (isHighRisk) {
                    riskCount++;
                }

                if (shouldInvokeAi(aiAnalyzedCount, isHighRisk)) {
                    AiAnalysisDetail aiDetail = aiSqlAnalyzer.analyze(sqlHash, scannedSql, ruleResult);
                    if (aiDetail != null) {
                        updateWithAiResult(analysis, aiDetail);
                        aiAnalyzedCount++;
                    }
                }
            }
        }

        batch.setTotalSqlCount(seenHashes.size());
        batch.setNewSqlCount(seenHashes.size());
        batch.setChangedSqlCount(0);
        batch.setRemovedSqlCount(0);
        batch.setRiskSqlCount(riskCount);

        log.info("Full scan completed: batchId={}, totalSql={}, riskSql={}, aiAnalyzed={}",
                batch.getId(), seenHashes.size(), riskCount, aiAnalyzedCount);
    }

    // ==================== Incremental Scan ====================

    private void doIncrementalScan(ScanBatch batch, ProjectConfig project,
                                    Path projectRoot, String headCommit) {
        String fromCommit = project.getLastScanCommit();
        batch.setFromCommit(fromCommit);

        DeltaResult delta = gitDeltaDetector.detectChanges(projectRoot, fromCommit, headCommit);

        if (!delta.hasChanges()) {
            log.info("No file changes detected between {}..{}", abbrev(fromCommit), abbrev(headCommit));
            batch.setTotalSqlCount(0);
            batch.setNewSqlCount(0);
            batch.setChangedSqlCount(0);
            batch.setRemovedSqlCount(0);
            batch.setRiskSqlCount(0);
            return;
        }

        int removedCount = 0;
        int newCount = 0;
        int riskCount = 0;
        int aiAnalyzedCount = 0;

        // Step 1: Handle deleted mapper XML files
        List<String> deletedFiles = delta.deletedMapperXmlFiles();
        if (!deletedFiles.isEmpty()) {
            removedCount += markRecordsAsRemoved(project.getId(), deletedFiles);
            log.info("Marked {} SQL records as removed from {} deleted files",
                    removedCount, deletedFiles.size());
        }

        // Step 2: Handle added/modified mapper XML files
        List<String> changedFiles = delta.changedMapperXmlFiles();
        if (!changedFiles.isEmpty()) {
            List<Path> absolutePaths = changedFiles.stream()
                    .map(projectRoot::resolve)
                    .toList();

            List<ScannedSql> currentSqls = mapperXmlScanner.scanFiles(projectRoot, absolutePaths);

            // Get existing records for these files
            List<SqlRecord> existingRecords = sqlRecordDbService.list(
                    new LambdaQueryWrapper<SqlRecord>()
                            .eq(SqlRecord::getProjectId, project.getId())
                            .in(SqlRecord::getSourceFile, changedFiles)
                            .eq(SqlRecord::getStatus, 1));

            ComparisonResult comparison = sqlHashComparator.compare(currentSqls, existingRecords);

            // Process new SQL: save + analyze
            Set<String> seenHashes = new HashSet<>();
            for (ScannedSql newSql : comparison.newSqls()) {
                String sqlHash = SqlNormalizer.hash(newSql.sqlNormalized());
                if (sqlHash.isBlank() || !seenHashes.add(sqlHash)) {
                    continue;
                }

                // Check if this hash already exists elsewhere in the project
                boolean existsGlobally = sqlRecordDbService.count(
                        new LambdaQueryWrapper<SqlRecord>()
                                .eq(SqlRecord::getProjectId, project.getId())
                                .eq(SqlRecord::getSqlHash, sqlHash)
                                .eq(SqlRecord::getStatus, 1)) > 0;

                if (existsGlobally) {
                    updateLastScanId(project.getId(), sqlHash, batch.getId());
                    continue;
                }

                SqlRecord record = saveSqlRecord(newSql, sqlHash, project.getId(), batch.getId());
                AnalysisResult ruleResult = ruleBasedAnalyzer.analyze(newSql);
                newCount++;

                if (!ruleResult.violations().isEmpty()) {
                    SqlAnalysis analysis = saveRuleAnalysisResult(record, batch.getId(), ruleResult);

                    boolean isHighRisk = ruleResult.riskLevel().ordinal() <= RiskLevel.P2.ordinal();
                    if (isHighRisk) {
                        riskCount++;
                    }

                    if (shouldInvokeAi(aiAnalyzedCount, isHighRisk)) {
                        AiAnalysisDetail aiDetail = aiSqlAnalyzer.analyze(sqlHash, newSql, ruleResult);
                        if (aiDetail != null) {
                            updateWithAiResult(analysis, aiDetail);
                            aiAnalyzedCount++;
                        }
                    }
                }
            }

            // Update lastScanId for unchanged SQL
            for (String unchangedHash : comparison.unchangedHashes()) {
                updateLastScanId(project.getId(), unchangedHash, batch.getId());
            }

            // Mark removed SQL from changed files
            for (SqlRecord removed : comparison.removedRecords()) {
                removed.setStatus(0);
                removed.setUpdateTime(LocalDateTime.now());
                sqlRecordDbService.updateById(removed);
                removedCount++;
            }
        }

        int activeInChangedFiles = changedFiles.isEmpty() ? 0 :
                (int) sqlRecordDbService.count(new LambdaQueryWrapper<SqlRecord>()
                        .eq(SqlRecord::getProjectId, project.getId())
                        .in(SqlRecord::getSourceFile, changedFiles)
                        .eq(SqlRecord::getStatus, 1));
        batch.setTotalSqlCount(newCount + activeInChangedFiles);
        batch.setNewSqlCount(newCount);
        batch.setChangedSqlCount(0);
        batch.setRemovedSqlCount(removedCount);
        batch.setRiskSqlCount(riskCount);

        log.info("Incremental scan completed: batchId={}, new={}, removed={}, risk={}, aiAnalyzed={}",
                batch.getId(), newCount, removedCount, riskCount, aiAnalyzedCount);
    }

    // ==================== Helper Methods ====================

    private boolean shouldInvokeAi(int aiAnalyzedCount, boolean isHighRisk) {
        if (!aiSqlAnalyzer.isAvailable() || !isHighRisk) {
            return false;
        }
        return maxAiCallsPerScan < 0 || aiAnalyzedCount < maxAiCallsPerScan;
    }

    private int markRecordsAsRemoved(Long projectId, List<String> sourceFiles) {
        List<SqlRecord> records = sqlRecordDbService.list(
                new LambdaQueryWrapper<SqlRecord>()
                        .eq(SqlRecord::getProjectId, projectId)
                        .in(SqlRecord::getSourceFile, sourceFiles)
                        .eq(SqlRecord::getStatus, 1));

        for (SqlRecord record : records) {
            record.setStatus(0);
            record.setUpdateTime(LocalDateTime.now());
            sqlRecordDbService.updateById(record);
        }
        return records.size();
    }

    private void updateLastScanId(Long projectId, String sqlHash, Long batchId) {
        SqlRecord update = new SqlRecord();
        update.setLastScanId(batchId);
        update.setUpdateTime(LocalDateTime.now());
        sqlRecordDbService.update(update,
                new LambdaQueryWrapper<SqlRecord>()
                        .eq(SqlRecord::getProjectId, projectId)
                        .eq(SqlRecord::getSqlHash, sqlHash)
                        .eq(SqlRecord::getStatus, 1));
    }

    private SqlRecord saveSqlRecord(ScannedSql scannedSql, String sqlHash,
                                     Long projectId, Long batchId) {
        SqlRecord record = new SqlRecord();
        record.setProjectId(projectId);
        record.setSqlHash(sqlHash);
        record.setSqlText(scannedSql.sql());
        record.setSqlNormalized(scannedSql.sqlNormalized());
        record.setSqlType(scannedSql.sqlType());
        record.setSourceType(scannedSql.sourceType().getCode());
        record.setSourceFile(scannedSql.sourceFile());
        record.setSourceLocation(scannedSql.sourceLocation());
        record.setFirstScanId(batchId);
        record.setLastScanId(batchId);
        record.setStatus(1);
        record.setCreateTime(LocalDateTime.now());
        record.setUpdateTime(LocalDateTime.now());
        sqlRecordDbService.save(record);
        return record;
    }

    private SqlAnalysis saveRuleAnalysisResult(SqlRecord record, Long batchId, AnalysisResult result) {
        SqlAnalysis analysis = new SqlAnalysis();
        analysis.setSqlRecordId(record.getId());
        analysis.setScanBatchId(batchId);
        analysis.setRuleRiskLevel(result.riskLevel().getCode());

        List<String> issues = result.violations().stream()
                .map(RuleViolation::message)
                .toList();
        analysis.setRuleIssues(JSONUtil.toJsonStr(issues));

        analysis.setFinalRiskLevel(result.riskLevel().getCode());
        analysis.setHandleStatus("PENDING");
        analysis.setCreateTime(LocalDateTime.now());
        sqlAnalysisDbService.save(analysis);
        return analysis;
    }

    private void updateWithAiResult(SqlAnalysis analysis, AiAnalysisDetail aiDetail) {
        SqlRiskAssessment ai = aiDetail.assessment();

        analysis.setAiRiskLevel(ai.riskLevel());
        analysis.setAiAnalysis(ai.explanation());
        analysis.setAiIndexSuggestion(JSONUtil.toJsonStr(ai.indexSuggestions()));
        analysis.setAiRewriteSuggestion(JSONUtil.toJsonStr(ai.rewriteSuggestions()));
        analysis.setAiEstimatedScanRows(ai.estimatedScanRows());
        analysis.setAiEstimatedExecTimeMs(ai.estimatedExecTimeMs());
        analysis.setAiModel(aiDetail.model());
        analysis.setAiTokensUsed(aiDetail.tokensUsed());

        String finalLevel = higherRisk(analysis.getRuleRiskLevel(), ai.riskLevel());
        analysis.setFinalRiskLevel(finalLevel);

        sqlAnalysisDbService.updateById(analysis);
    }

    private String higherRisk(String a, String b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.compareTo(b) <= 0 ? a : b;
    }

    private static String abbrev(String hash) {
        return hash != null && hash.length() > 8 ? hash.substring(0, 8) : hash;
    }
}

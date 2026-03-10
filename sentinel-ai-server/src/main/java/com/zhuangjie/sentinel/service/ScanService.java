package com.zhuangjie.sentinel.service;

import com.zhuangjie.sentinel.analyzer.AiSqlAnalyzer;
import com.zhuangjie.sentinel.analyzer.AiSqlAnalyzer.AiAnalysisDetail;
import com.zhuangjie.sentinel.analyzer.AnalysisResult;
import com.zhuangjie.sentinel.analyzer.RuleBasedAnalyzer;
import com.zhuangjie.sentinel.analyzer.rules.RuleViolation;
import com.zhuangjie.sentinel.db.entity.ProjectConfig;
import com.zhuangjie.sentinel.db.entity.ScanBatch;
import com.zhuangjie.sentinel.db.entity.SqlAnalysis;
import com.zhuangjie.sentinel.db.entity.SqlRecord;
import com.zhuangjie.sentinel.db.service.ScanBatchDbService;
import com.zhuangjie.sentinel.db.service.SqlAnalysisDbService;
import com.zhuangjie.sentinel.db.service.SqlRecordDbService;
import com.zhuangjie.sentinel.pojo.dto.ScannedSql;
import com.zhuangjie.sentinel.pojo.dto.SqlRiskAssessment;
import com.zhuangjie.sentinel.pojo.enums.RiskLevel;
import com.zhuangjie.sentinel.pojo.enums.ScanType;
import com.zhuangjie.sentinel.scanner.MapperXmlScanner;
import com.zhuangjie.sentinel.scanner.SqlNormalizer;
import cn.hutool.json.JSONUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
    private final ScanBatchDbService scanBatchDbService;
    private final SqlRecordDbService sqlRecordDbService;
    private final SqlAnalysisDbService sqlAnalysisDbService;
    private final MapperXmlScanner mapperXmlScanner;
    private final RuleBasedAnalyzer ruleBasedAnalyzer;
    private final AiSqlAnalyzer aiSqlAnalyzer;

    @Async
    public CompletableFuture<ScanBatch> triggerScan(Long projectId) {
        ProjectConfig project = projectService.getById(projectId);
        if (project == null) {
            throw new IllegalArgumentException("Project not found: " + projectId);
        }

        boolean isFullScan = project.getLastScanCommit() == null;
        ScanType scanType = isFullScan ? ScanType.FULL : ScanType.INCREMENTAL;

        ScanBatch batch = new ScanBatch();
        batch.setProjectId(projectId);
        batch.setScanType(scanType.getCode());
        batch.setStatus("RUNNING");
        batch.setCreateTime(LocalDateTime.now());
        scanBatchDbService.save(batch);

        long startTime = System.currentTimeMillis();

        try {
            log.info("Scan started: projectId={}, type={}, batchId={}, aiEnabled={}",
                    projectId, scanType, batch.getId(), aiSqlAnalyzer.isAvailable());

            Path projectRoot = Path.of(project.getGitRepoPath());
            List<ScannedSql> scannedSqls = mapperXmlScanner.scan(projectRoot);

            int riskCount = 0;
            int aiAnalyzedCount = 0;
            Set<String> seenHashes = new HashSet<>();

            for (ScannedSql scannedSql : scannedSqls) {
                String sqlHash = SqlNormalizer.hash(scannedSql.sqlNormalized());
                if (sqlHash.isBlank() || !seenHashes.add(sqlHash)) {
                    continue;
                }

                SqlRecord record = saveSqlRecord(scannedSql, sqlHash, projectId, batch.getId());
                AnalysisResult ruleResult = ruleBasedAnalyzer.analyze(scannedSql);

                if (!ruleResult.violations().isEmpty()) {
                    SqlAnalysis analysis = saveRuleAnalysisResult(record, batch.getId(), ruleResult);

                    boolean isHighRisk = ruleResult.riskLevel().ordinal() <= RiskLevel.P2.ordinal();
                    if (isHighRisk) {
                        riskCount++;
                    }

                    if (aiSqlAnalyzer.isAvailable() && isHighRisk) {
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
            batch.setStatus("COMPLETED");

            log.info("Scan completed: batchId={}, totalSql={}, riskSql={}, aiAnalyzed={}",
                    batch.getId(), seenHashes.size(), riskCount, aiAnalyzedCount);
        } catch (Exception e) {
            log.error("Scan failed: projectId={}, batchId={}", projectId, batch.getId(), e);
            batch.setStatus("FAILED");
        } finally {
            batch.setScanDurationMs(System.currentTimeMillis() - startTime);
            scanBatchDbService.updateById(batch);
        }

        return CompletableFuture.completedFuture(batch);
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

    /**
     * Returns the higher (more severe) risk level between two risk codes.
     * Lower ordinal = higher severity: P0 > P1 > P2 > P3 > P4.
     */
    private String higherRisk(String a, String b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.compareTo(b) <= 0 ? a : b;
    }
}

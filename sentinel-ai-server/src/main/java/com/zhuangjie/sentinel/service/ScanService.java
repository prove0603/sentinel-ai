package com.zhuangjie.sentinel.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.zhuangjie.sentinel.analyzer.AiSqlAnalyzer;
import com.zhuangjie.sentinel.analyzer.AiSqlAnalyzer.AiAnalysisDetail;
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
import com.zhuangjie.sentinel.delta.GitRepoManager;
import com.zhuangjie.sentinel.delta.SqlHashComparator;
import com.zhuangjie.sentinel.notification.UserMappingService;
import com.zhuangjie.sentinel.notification.WeComNotificationService;
import com.zhuangjie.sentinel.pojo.dto.ScannedSql;
import com.zhuangjie.sentinel.pojo.dto.SqlRiskAssessment;
import com.zhuangjie.sentinel.pojo.enums.ScanType;
import com.zhuangjie.sentinel.scanner.AnnotationSqlScanner;
import com.zhuangjie.sentinel.scanner.MapperXmlScanner;
import com.zhuangjie.sentinel.scanner.QueryWrapperScanner;
import com.zhuangjie.sentinel.scanner.SqlNormalizer;
import cn.hutool.json.JSONUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

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
    private final AnnotationSqlScanner annotationSqlScanner;
    private final QueryWrapperScanner queryWrapperScanner;
    private final AiSqlAnalyzer aiSqlAnalyzer;
    private final GitDeltaDetector gitDeltaDetector;
    private final GitRepoManager gitRepoManager;
    private final SqlHashComparator sqlHashComparator;
    private final ExemptionService exemptionService;
    private final UserMappingService userMappingService;

    @Autowired(required = false)
    private WeComNotificationService weComNotificationService;

    @Value("${sentinel.ai.max-ai-calls-per-scan:-1}")
    private int maxAiCallsPerScan;

    @Value("${sentinel.dev.max-sql-per-scan:-1}")
    private int maxSqlPerScan;

    @Async
    public CompletableFuture<ScanBatch> triggerScan(Long projectId, boolean forceFullScan) {
        ProjectConfig project = projectService.getById(projectId);
        if (project == null) {
            throw new IllegalArgumentException("Project not found: " + projectId);
        }

        Path projectRoot;
        try {
            projectRoot = gitRepoManager.syncRepo(project);
        } catch (Exception e) {
            log.error("Failed to sync git repo for project {}: {}", project.getProjectName(), e.getMessage());
            throw new RuntimeException("Git sync failed: " + e.getMessage(), e);
        }

        String headCommit = gitDeltaDetector.resolveHead(projectRoot);
        String gitAuthor = gitDeltaDetector.resolveHeadAuthor(projectRoot);

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
            log.info("Scan started: projectId={}, type={}, batchId={}, aiEnabled={}, maxAiCalls={}, maxSql={}",
                    projectId, scanType, batch.getId(), aiSqlAnalyzer.isAvailable(),
                    maxAiCallsPerScan < 0 ? "unlimited" : maxAiCallsPerScan,
                    maxSqlPerScan < 0 ? "unlimited" : maxSqlPerScan);

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

        if (weComNotificationService != null) {
            weComNotificationService.sendScanNotification(batch, project.getProjectName(), gitAuthor);
        }

        return CompletableFuture.completedFuture(batch);
    }

    // ==================== Full Scan ====================

    /**
     * 全量扫描（upsert 模式）：
     * 1. 扫描所有源码提取 SQL
     * 2. 对每条 SQL 按 sqlHash 判断 DB 中是否已有活跃记录：
     *    - 已存在且有分析结果 → 更新 lastScanId，跳过 AI（计入 reused）
     *    - 已存在但无分析结果 → 更新 lastScanId + 源码位置，调 AI 补充分析
     *    - 不存在 → 新建 SqlRecord + 调 AI
     * 3. 将本次未扫描到但之前存在的 SQL 标记为 removed（status=0）
     */
    private void doFullScan(ScanBatch batch, ProjectConfig project, Path projectRoot) {
        List<ScannedSql> scannedSqls = scanAllSources(projectRoot);

        Map<String, String> fileOwnerMap = resolveFileOwners(projectRoot, scannedSqls);

        int newCount = 0;
        int riskCount = 0;
        int aiAnalyzedCount = 0;
        int reusedCount = 0;
        int exemptedCount = 0;
        Set<String> seenHashes = new HashSet<>();
        Set<String> scannedHashesThisBatch = new HashSet<>();

        for (ScannedSql scannedSql : scannedSqls) {
            String sqlHash = SqlNormalizer.hash(scannedSql.sqlNormalized());
            if (sqlHash.isBlank() || !seenHashes.add(sqlHash)) {
                continue;
            }

            if (maxSqlPerScan > 0 && seenHashes.size() > maxSqlPerScan) {
                log.info("Dev limit reached: max-sql-per-scan={}, stopping scan", maxSqlPerScan);
                break;
            }

            com.zhuangjie.sentinel.db.entity.ExemptionRule exemption =
                    exemptionService.match(scannedSql, project.getId());
            if (exemption != null) {
                exemptedCount++;
                log.debug("SQL exempted (skipped entirely): rule=#{}({}:{}), source={}",
                        exemption.getId(), exemption.getRuleType(), exemption.getPattern(),
                        scannedSql.sourceLocation());
                continue;
            }

            scannedHashesThisBatch.add(sqlHash);

            SqlRecord existing = findExistingRecord(project.getId(), sqlHash);
            if (existing != null) {
                existing.setLastScanId(batch.getId());
                existing.setSourceFile(scannedSql.sourceFile());
                existing.setSourceLocation(scannedSql.sourceLocation());
                existing.setOwner(fileOwnerMap.getOrDefault(scannedSql.sourceFile(), userMappingService.getDefaultOwner()));
                existing.setUpdateTime(LocalDateTime.now());
                sqlRecordDbService.updateById(existing);

                SqlAnalysis existingAnalysis = findLatestAnalysis(existing.getId());
                if (existingAnalysis != null) {
                    reusedCount++;
                    if (isHighRisk(existingAnalysis.getFinalRiskLevel())) {
                        riskCount++;
                    }
                    continue;
                }

                if (shouldInvokeAi(aiAnalyzedCount)) {
                    AiAnalysisDetail aiDetail = aiSqlAnalyzer.analyze(sqlHash, scannedSql, project.getProjectName());
                    if (aiDetail != null) {
                        saveAiAnalysisResult(existing, batch.getId(), aiDetail);
                        aiAnalyzedCount++;
                        if (isHighRisk(aiDetail.assessment().riskLevel())) {
                            riskCount++;
                        }
                    }
                }
            } else {
                String owner = fileOwnerMap.getOrDefault(scannedSql.sourceFile(), userMappingService.getDefaultOwner());
                SqlRecord record = saveSqlRecord(scannedSql, sqlHash, project.getId(), batch.getId(), owner);
                newCount++;

                if (shouldInvokeAi(aiAnalyzedCount)) {
                    AiAnalysisDetail aiDetail = aiSqlAnalyzer.analyze(sqlHash, scannedSql, project.getProjectName());
                    if (aiDetail != null) {
                        saveAiAnalysisResult(record, batch.getId(), aiDetail);
                        aiAnalyzedCount++;
                        if (isHighRisk(aiDetail.assessment().riskLevel())) {
                            riskCount++;
                        }
                    }
                }
            }
        }

        int removedCount = markRemovedInFullScan(project.getId(), batch.getId(), scannedHashesThisBatch);

        batch.setTotalSqlCount(seenHashes.size());
        batch.setNewSqlCount(newCount);
        batch.setChangedSqlCount(0);
        batch.setRemovedSqlCount(removedCount);
        batch.setRiskSqlCount(riskCount);

        log.info("Full scan completed: batchId={}, totalSql={}, newSql={}, riskSql={}, aiAnalyzed={}, reused={}, exempted={}, removed={}",
                batch.getId(), seenHashes.size(), newCount, riskCount, aiAnalyzedCount, reusedCount, exemptedCount, removedCount);
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
        int reusedCount = 0;

        List<String> deletedFiles = delta.deletedMapperXmlFiles();
        if (!deletedFiles.isEmpty()) {
            removedCount += markRecordsAsRemoved(project.getId(), deletedFiles);
        }

        List<String> changedXmlFiles = delta.changedMapperXmlFiles();
        if (!changedXmlFiles.isEmpty()) {
            List<Path> xmlAbsolutePaths = changedXmlFiles.stream()
                    .map(projectRoot::resolve)
                    .toList();
            List<ScannedSql> xmlSqls = mapperXmlScanner.scanFiles(projectRoot, xmlAbsolutePaths);
            Map<String, String> xmlOwnerMap = resolveFileOwners(projectRoot, xmlSqls);
            IncrementalProcessResult xmlResult = processIncrementalSqls(
                    xmlSqls, changedXmlFiles, project, batch, aiAnalyzedCount, xmlOwnerMap);
            newCount += xmlResult.newCount;
            removedCount += xmlResult.removedCount;
            riskCount += xmlResult.riskCount;
            aiAnalyzedCount += xmlResult.aiAnalyzedCount;
            reusedCount += xmlResult.reusedCount;
        }

        List<String> changedJavaFiles = delta.changedJavaFiles();
        if (!changedJavaFiles.isEmpty()) {
            List<Path> javaAbsolutePaths = changedJavaFiles.stream()
                    .map(projectRoot::resolve)
                    .toList();
            List<ScannedSql> javaSqls = new ArrayList<>();
            javaSqls.addAll(annotationSqlScanner.scanFiles(projectRoot, javaAbsolutePaths));
            javaSqls.addAll(queryWrapperScanner.scanFiles(projectRoot, javaAbsolutePaths));

            if (!javaSqls.isEmpty()) {
                Map<String, String> javaOwnerMap = resolveFileOwners(projectRoot, javaSqls);
                IncrementalProcessResult javaResult = processIncrementalSqls(
                        javaSqls, changedJavaFiles, project, batch, aiAnalyzedCount, javaOwnerMap);
                newCount += javaResult.newCount;
                removedCount += javaResult.removedCount;
                riskCount += javaResult.riskCount;
                aiAnalyzedCount += javaResult.aiAnalyzedCount;
                reusedCount += javaResult.reusedCount;
            }
        }

        List<String> deletedJavaFiles = delta.deletedJavaFiles();
        if (!deletedJavaFiles.isEmpty()) {
            removedCount += markRecordsAsRemoved(project.getId(), deletedJavaFiles);
        }

        List<String> allChangedFiles = new ArrayList<>();
        allChangedFiles.addAll(delta.changedMapperXmlFiles());
        allChangedFiles.addAll(delta.changedJavaFiles());

        int activeInChangedFiles = allChangedFiles.isEmpty() ? 0 :
                (int) sqlRecordDbService.count(new LambdaQueryWrapper<SqlRecord>()
                        .eq(SqlRecord::getProjectId, project.getId())
                        .in(SqlRecord::getSourceFile, allChangedFiles)
                        .eq(SqlRecord::getStatus, 1));
        batch.setTotalSqlCount(newCount + activeInChangedFiles);
        batch.setNewSqlCount(newCount);
        batch.setChangedSqlCount(0);
        batch.setRemovedSqlCount(removedCount);
        batch.setRiskSqlCount(riskCount);

        log.info("Incremental scan completed: batchId={}, new={}, removed={}, risk={}, aiAnalyzed={}, reused={}",
                batch.getId(), newCount, removedCount, riskCount, aiAnalyzedCount, reusedCount);
    }

    // ==================== Incremental Processing ====================

    private record IncrementalProcessResult(int newCount, int removedCount, int riskCount, int aiAnalyzedCount, int reusedCount) {
    }

    private IncrementalProcessResult processIncrementalSqls(
            List<ScannedSql> currentSqls, List<String> changedFiles,
            ProjectConfig project, ScanBatch batch, int baseAiAnalyzedCount,
            Map<String, String> fileOwnerMap) {

        List<SqlRecord> existingRecords = sqlRecordDbService.list(
                new LambdaQueryWrapper<SqlRecord>()
                        .eq(SqlRecord::getProjectId, project.getId())
                        .in(SqlRecord::getSourceFile, changedFiles)
                        .eq(SqlRecord::getStatus, 1));

        ComparisonResult comparison = sqlHashComparator.compare(currentSqls, existingRecords);

        int newCount = 0;
        int removedCount = 0;
        int riskCount = 0;
        int aiAnalyzedCount = 0;
        int reusedCount = 0;
        Set<String> seenHashes = new HashSet<>();

        for (ScannedSql newSql : comparison.newSqls()) {
            String sqlHash = SqlNormalizer.hash(newSql.sqlNormalized());
            if (sqlHash.isBlank() || !seenHashes.add(sqlHash)) {
                continue;
            }

            if (maxSqlPerScan > 0 && newCount >= maxSqlPerScan) {
                log.info("Dev limit reached: max-sql-per-scan={}, stopping incremental processing", maxSqlPerScan);
                break;
            }

            boolean existsGlobally = sqlRecordDbService.count(
                    new LambdaQueryWrapper<SqlRecord>()
                            .eq(SqlRecord::getProjectId, project.getId())
                            .eq(SqlRecord::getSqlHash, sqlHash)
                            .eq(SqlRecord::getStatus, 1)) > 0;

            if (existsGlobally) {
                updateLastScanId(project.getId(), sqlHash, batch.getId());
                continue;
            }

            com.zhuangjie.sentinel.db.entity.ExemptionRule exemption =
                    exemptionService.match(newSql, project.getId());
            if (exemption != null) {
                log.debug("SQL exempted (skipped entirely): rule=#{}({}:{}), source={}",
                        exemption.getId(), exemption.getRuleType(), exemption.getPattern(),
                        newSql.sourceLocation());
                continue;
            }

            String owner = fileOwnerMap.getOrDefault(newSql.sourceFile(), userMappingService.getDefaultOwner());
            SqlRecord record = saveSqlRecord(newSql, sqlHash, project.getId(), batch.getId(), owner);
            newCount++;

            SqlAnalysis reused = reuseExistingAnalysis(sqlHash, record.getId(), batch.getId());
            if (reused != null) {
                reusedCount++;
                if (isHighRisk(reused.getFinalRiskLevel())) {
                    riskCount++;
                }
                continue;
            }

            if (shouldInvokeAi(baseAiAnalyzedCount + aiAnalyzedCount)) {
                AiAnalysisDetail aiDetail = aiSqlAnalyzer.analyze(sqlHash, newSql, project.getProjectName());
                if (aiDetail != null) {
                    saveAiAnalysisResult(record, batch.getId(), aiDetail);
                    aiAnalyzedCount++;
                    if (isHighRisk(aiDetail.assessment().riskLevel())) {
                        riskCount++;
                    }
                }
            }
        }

        for (String unchangedHash : comparison.unchangedHashes()) {
            updateLastScanId(project.getId(), unchangedHash, batch.getId());
        }

        for (SqlRecord removed : comparison.removedRecords()) {
            removed.setStatus(0);
            removed.setUpdateTime(LocalDateTime.now());
            sqlRecordDbService.updateById(removed);
            removedCount++;
        }

        return new IncrementalProcessResult(newCount, removedCount, riskCount, aiAnalyzedCount, reusedCount);
    }

    // ==================== Multi-Source Scanning ====================

    private List<ScannedSql> scanAllSources(Path projectRoot) {
        List<ScannedSql> all = new ArrayList<>();
        all.addAll(mapperXmlScanner.scan(projectRoot));
        all.addAll(annotationSqlScanner.scan(projectRoot));
        all.addAll(queryWrapperScanner.scan(projectRoot));
        log.info("All scanners combined: {} SQL statements (XML={}, annotation={}, wrapper={})",
                all.size(),
                all.stream().filter(s -> s.sourceType() == com.zhuangjie.sentinel.pojo.enums.SqlSourceType.MAPPER_XML).count(),
                all.stream().filter(s -> s.sourceType() == com.zhuangjie.sentinel.pojo.enums.SqlSourceType.ANNOTATION).count(),
                all.stream().filter(s -> s.sourceType() == com.zhuangjie.sentinel.pojo.enums.SqlSourceType.QUERY_WRAPPER
                        || s.sourceType() == com.zhuangjie.sentinel.pojo.enums.SqlSourceType.LAMBDA_WRAPPER).count());
        return all;
    }

    // ==================== Record Lookup & Analysis Cache ====================

    /**
     * 按 sqlHash 查找项目中已存在的活跃 SqlRecord（用于 upsert 判断）。
     */
    private SqlRecord findExistingRecord(Long projectId, String sqlHash) {
        return sqlRecordDbService.getOne(
                new LambdaQueryWrapper<SqlRecord>()
                        .eq(SqlRecord::getProjectId, projectId)
                        .eq(SqlRecord::getSqlHash, sqlHash)
                        .eq(SqlRecord::getStatus, 1)
                        .last("LIMIT 1"));
    }

    /**
     * 查找某条 SqlRecord 的最新分析结果。
     */
    private SqlAnalysis findLatestAnalysis(Long sqlRecordId) {
        return sqlAnalysisDbService.getOne(
                new LambdaQueryWrapper<SqlAnalysis>()
                        .eq(SqlAnalysis::getSqlRecordId, sqlRecordId)
                        .orderByDesc(SqlAnalysis::getCreateTime)
                        .last("LIMIT 1"));
    }

    /**
     * 全量扫描结束后，将本次未扫到的 SQL 标记为 removed（status=0）。
     * 说明该 SQL 已从源码中删除。
     */
    private int markRemovedInFullScan(Long projectId, Long batchId, Set<String> scannedHashes) {
        List<SqlRecord> allActive = sqlRecordDbService.list(
                new LambdaQueryWrapper<SqlRecord>()
                        .eq(SqlRecord::getProjectId, projectId)
                        .eq(SqlRecord::getStatus, 1));
        int removedCount = 0;
        for (SqlRecord record : allActive) {
            if (!scannedHashes.contains(record.getSqlHash())) {
                record.setStatus(0);
                record.setUpdateTime(LocalDateTime.now());
                sqlRecordDbService.updateById(record);
                removedCount++;
            }
        }
        if (removedCount > 0) {
            log.info("Full scan: marked {} SQL records as removed (no longer in source code)", removedCount);
        }
        return removedCount;
    }

    /**
     * 增量扫描用：检查同一 SQL（按 hash）是否在之前的扫描中已被分析过。
     * 若存在历史分析结果，复制一份关联到新的 SqlRecord，避免重复调用 AI。
     */
    private SqlAnalysis reuseExistingAnalysis(String sqlHash, Long newRecordId, Long batchId) {
        List<SqlRecord> priorRecords = sqlRecordDbService.list(
                new LambdaQueryWrapper<SqlRecord>()
                        .eq(SqlRecord::getSqlHash, sqlHash)
                        .eq(SqlRecord::getStatus, 1)
                        .ne(SqlRecord::getId, newRecordId)
                        .last("LIMIT 1"));
        if (priorRecords.isEmpty()) {
            return null;
        }

        SqlRecord priorRecord = priorRecords.get(0);
        SqlAnalysis priorAnalysis = findLatestAnalysis(priorRecord.getId());
        if (priorAnalysis == null) {
            return null;
        }

        SqlAnalysis reused = new SqlAnalysis();
        reused.setSqlRecordId(newRecordId);
        reused.setScanBatchId(batchId);
        reused.setAiRiskLevel(priorAnalysis.getAiRiskLevel());
        reused.setAiAnalysis(priorAnalysis.getAiAnalysis());
        reused.setAiIndexSuggestion(priorAnalysis.getAiIndexSuggestion());
        reused.setAiRewriteSuggestion(priorAnalysis.getAiRewriteSuggestion());
        reused.setAiEstimatedScanRows(priorAnalysis.getAiEstimatedScanRows());
        reused.setAiEstimatedExecTimeMs(priorAnalysis.getAiEstimatedExecTimeMs());
        reused.setAiModel(priorAnalysis.getAiModel());
        reused.setAiTokensUsed(0);
        reused.setFinalRiskLevel(priorAnalysis.getFinalRiskLevel());
        reused.setHandleStatus("ANALYZED");
        reused.setHandleNote("Reused from prior analysis (record #" + priorRecord.getId() + ")");
        reused.setCreateTime(LocalDateTime.now());
        sqlAnalysisDbService.save(reused);

        log.debug("Reused prior analysis for sqlHash={}, priorRecordId={}, newRecordId={}",
                sqlHash, priorRecord.getId(), newRecordId);
        return reused;
    }

    // ==================== Helper Methods ====================

    private boolean shouldInvokeAi(int aiAnalyzedCount) {
        if (!aiSqlAnalyzer.isAvailable()) {
            return false;
        }
        return maxAiCallsPerScan < 0 || aiAnalyzedCount < maxAiCallsPerScan;
    }

    private boolean isHighRisk(String riskLevel) {
        if (riskLevel == null) return false;
        return riskLevel.compareTo("P2") <= 0;
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

    /**
     * 批量解析 SQL 来源文件的负责人。
     * Git 用户名 → 企业微信 userId，找不到映射则使用默认负责人。
     */
    private Map<String, String> resolveFileOwners(Path projectRoot, List<ScannedSql> sqls) {
        List<String> uniqueFiles = sqls.stream()
                .map(ScannedSql::sourceFile)
                .distinct()
                .collect(Collectors.toList());

        Map<String, String> gitAuthors = gitDeltaDetector.resolveFileAuthors(projectRoot, uniqueFiles);

        return uniqueFiles.stream().collect(Collectors.toMap(
                f -> f,
                f -> userMappingService.resolveOwner(gitAuthors.get(f)),
                (a, b) -> a
        ));
    }

    private SqlRecord saveSqlRecord(ScannedSql scannedSql, String sqlHash,
                                     Long projectId, Long batchId, String owner) {
        SqlRecord record = new SqlRecord();
        record.setProjectId(projectId);
        record.setSqlHash(sqlHash);
        record.setSqlText(scannedSql.sql());
        record.setSqlNormalized(scannedSql.sqlNormalized());
        record.setSqlType(scannedSql.sqlType());
        record.setSourceType(scannedSql.sourceType().getCode());
        record.setSourceFile(scannedSql.sourceFile());
        record.setSourceLocation(scannedSql.sourceLocation());
        record.setOwner(owner);
        record.setFirstScanId(batchId);
        record.setLastScanId(batchId);
        record.setStatus(1);
        record.setCreateTime(LocalDateTime.now());
        record.setUpdateTime(LocalDateTime.now());
        sqlRecordDbService.save(record);
        return record;
    }

    private SqlAnalysis saveAiAnalysisResult(SqlRecord record, Long batchId, AiAnalysisDetail aiDetail) {
        SqlRiskAssessment ai = aiDetail.assessment();

        SqlAnalysis analysis = new SqlAnalysis();
        analysis.setSqlRecordId(record.getId());
        analysis.setScanBatchId(batchId);
        analysis.setAiRiskLevel(ai.riskLevel());
        analysis.setAiAnalysis(ai.explanation());
        analysis.setAiIndexSuggestion(JSONUtil.toJsonStr(ai.indexSuggestions()));
        analysis.setAiRewriteSuggestion(JSONUtil.toJsonStr(ai.rewriteSuggestions()));
        analysis.setAiEstimatedScanRows(ai.estimatedScanRows());
        analysis.setAiEstimatedExecTimeMs(ai.estimatedExecTimeMs());
        analysis.setAiModel(aiDetail.model());
        analysis.setAiTokensUsed(aiDetail.tokensUsed());
        analysis.setFinalRiskLevel(ai.riskLevel());
        analysis.setHandleStatus("ANALYZED");
        analysis.setCreateTime(LocalDateTime.now());
        sqlAnalysisDbService.save(analysis);
        return analysis;
    }

    /**
     * 工具方法：为指定项目中 owner 为空的活跃 SqlRecord 补充负责人。
     * 通过 Git log 获取文件最新提交人，再映射到系统 owner。
     *
     * @return 更新的记录数
     */
    public int fillMissingOwners(Long projectId) {
        ProjectConfig project = projectService.getById(projectId);
        if (project == null) {
            throw new IllegalArgumentException("Project not found: " + projectId);
        }

        Path projectRoot;
        try {
            projectRoot = gitRepoManager.syncRepo(project);
        } catch (Exception e) {
            throw new RuntimeException("Git sync failed: " + e.getMessage(), e);
        }

        List<SqlRecord> records = sqlRecordDbService.list(
                new LambdaQueryWrapper<SqlRecord>()
                        .eq(SqlRecord::getProjectId, projectId)
                        .eq(SqlRecord::getStatus, 1)
                        .and(w -> w.isNull(SqlRecord::getOwner).or().eq(SqlRecord::getOwner, "")));

        if (records.isEmpty()) {
            log.info("fillMissingOwners: no records need owner for project {}", projectId);
            return 0;
        }

        List<String> uniqueFiles = records.stream()
                .map(SqlRecord::getSourceFile)
                .distinct()
                .collect(Collectors.toList());

        Map<String, String> gitAuthors = gitDeltaDetector.resolveFileAuthors(projectRoot, uniqueFiles);
        Map<String, String> fileOwnerMap = uniqueFiles.stream().collect(Collectors.toMap(
                f -> f,
                f -> userMappingService.resolveOwner(gitAuthors.get(f)),
                (a, b) -> a
        ));

        int updated = 0;
        for (SqlRecord record : records) {
            String owner = fileOwnerMap.getOrDefault(record.getSourceFile(), userMappingService.getDefaultOwner());
            record.setOwner(owner);
            record.setUpdateTime(LocalDateTime.now());
            sqlRecordDbService.updateById(record);
            updated++;
        }

        log.info("fillMissingOwners: updated {} records for project {}", updated, projectId);
        return updated;
    }

    /**
     * 工具方法：为所有项目补充 owner。
     */
    public int fillAllMissingOwners() {
        List<ProjectConfig> projects = projectConfigDbService.list(
                new LambdaQueryWrapper<ProjectConfig>().eq(ProjectConfig::getStatus, 1));
        int total = 0;
        for (ProjectConfig project : projects) {
            try {
                total += fillMissingOwners(project.getId());
            } catch (Exception e) {
                log.warn("fillMissingOwners failed for project {}: {}", project.getProjectName(), e.getMessage());
            }
        }
        return total;
    }

    private static String abbrev(String hash) {
        return hash != null && hash.length() > 8 ? hash.substring(0, 8) : hash;
    }
}

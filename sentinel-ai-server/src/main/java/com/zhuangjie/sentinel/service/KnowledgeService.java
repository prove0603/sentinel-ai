package com.zhuangjie.sentinel.service;

import com.zhuangjie.sentinel.db.entity.ProjectConfig;
import com.zhuangjie.sentinel.knowledge.TableMetaCollector;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Orchestrates knowledge base operations using file-based table metadata storage.
 * DDL files are stored as {@code {table-meta-dir}/{project-name}/{table_name}.sql}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeService {

    private final TableMetaCollector tableMetaCollector;
    private final ProjectService projectService;

    @Value("${sentinel.knowledge.table-meta-dir:table-meta}")
    private String tableMetaDir;

    /**
     * Auto-collects table metadata from the project's configured database
     * and saves as DDL files in the table-meta directory.
     *
     * @return number of tables collected
     */
    public int autoCollect(Long projectId) {
        ProjectConfig project = projectService.getById(projectId);
        if (project == null) {
            throw new IllegalArgumentException("Project not found: " + projectId);
        }
        if (project.getDbConnectionUrl() == null || project.getDbConnectionUrl().isBlank()) {
            throw new IllegalStateException("No db_connection_url configured for project: " + projectId);
        }

        String projectName = project.getProjectName();
        Path projectDir = Path.of(tableMetaDir, projectName);

        List<TableMetaCollector.CollectedTable> collected =
                tableMetaCollector.collect(project.getDbConnectionUrl());

        if (collected.isEmpty()) {
            return 0;
        }

        try {
            Files.createDirectories(projectDir);
        } catch (IOException e) {
            log.error("Failed to create table-meta directory: {}", projectDir, e);
            return 0;
        }

        int saved = 0;
        for (TableMetaCollector.CollectedTable table : collected) {
            Path file = projectDir.resolve(table.tableName() + ".sql");
            try {
                String content = buildFileContent(table);
                Files.writeString(file, content, StandardCharsets.UTF_8);
                saved++;
            } catch (IOException e) {
                log.warn("Failed to write DDL file for {}: {}", table.tableName(), e.getMessage());
            }
        }

        log.info("Saved {} DDL files to {}", saved, projectDir);
        return saved;
    }

    /**
     * Lists all DDL files for a project.
     */
    public List<String> listTables(String projectName) {
        Path projectDir = Path.of(tableMetaDir, projectName);
        if (!Files.isDirectory(projectDir)) {
            return List.of();
        }
        try (var stream = Files.list(projectDir)) {
            return stream
                    .filter(p -> p.toString().endsWith(".sql"))
                    .map(p -> p.getFileName().toString().replace(".sql", ""))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            log.warn("Failed to list table-meta directory: {}", projectDir, e);
            return List.of();
        }
    }

    private String buildFileContent(TableMetaCollector.CollectedTable table) {
        StringBuilder sb = new StringBuilder();
        sb.append("-- Estimated Rows: ").append(table.estimatedRows()).append("\n\n");
        sb.append(table.ddl()).append("\n");
        return sb.toString();
    }
}

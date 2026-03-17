package com.zhuangjie.sentinel.rag;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.zhuangjie.sentinel.db.entity.KnowledgeEntry;
import com.zhuangjie.sentinel.db.service.KnowledgeEntryDbService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Business knowledge RAG service:
 * - CRUD for knowledge entries (stored in H2)
 * - Embed & store in PGVector for semantic retrieval
 * - Retrieve relevant knowledge for AI SQL analysis
 */
@Slf4j
@Service
public class KnowledgeRagService {

    private static final int DEFAULT_TOP_K = 3;
    private static final double DEFAULT_SIMILARITY_THRESHOLD = 0.5;

    private final KnowledgeEntryDbService knowledgeEntryDbService;
    private final VectorStore vectorStore;
    private final JdbcTemplate jdbcTemplate;

    public KnowledgeRagService(
            KnowledgeEntryDbService knowledgeEntryDbService,
            @Autowired(required = false) VectorStore vectorStore,
            @Autowired(required = false) JdbcTemplate jdbcTemplate) {
        this.knowledgeEntryDbService = knowledgeEntryDbService;
        this.vectorStore = vectorStore;
        this.jdbcTemplate = jdbcTemplate;
        log.info("KnowledgeRagService initialized, vectorStore: {}",
                vectorStore != null ? "available" : "unavailable (RAG disabled)");
    }

    public boolean isRagAvailable() {
        return vectorStore != null;
    }

    // ==================== CRUD ====================

    public KnowledgeEntry create(KnowledgeEntry entry) {
        entry.setEmbedded(0);
        entry.setStatus(1);
        entry.setCreateTime(LocalDateTime.now());
        entry.setUpdateTime(LocalDateTime.now());
        knowledgeEntryDbService.save(entry);
        embedEntry(entry);
        return entry;
    }

    public KnowledgeEntry update(KnowledgeEntry entry) {
        entry.setEmbedded(0);
        entry.setUpdateTime(LocalDateTime.now());
        knowledgeEntryDbService.updateById(entry);
        removeFromVectorStore(entry.getId());
        embedEntry(entry);
        return entry;
    }

    public void delete(Long id) {
        removeFromVectorStore(id);
        KnowledgeEntry entry = new KnowledgeEntry();
        entry.setId(id);
        entry.setStatus(0);
        entry.setUpdateTime(LocalDateTime.now());
        knowledgeEntryDbService.updateById(entry);
    }

    public KnowledgeEntry getById(Long id) {
        return knowledgeEntryDbService.getById(id);
    }

    public Page<KnowledgeEntry> page(int current, int size, String knowledgeType) {
        LambdaQueryWrapper<KnowledgeEntry> wrapper = new LambdaQueryWrapper<KnowledgeEntry>()
                .eq(KnowledgeEntry::getStatus, 1)
                .eq(knowledgeType != null && !knowledgeType.isBlank(),
                        KnowledgeEntry::getKnowledgeType, knowledgeType)
                .orderByDesc(KnowledgeEntry::getCreateTime);
        return knowledgeEntryDbService.page(new Page<>(current, size), wrapper);
    }

    public List<KnowledgeEntry> listAll() {
        return knowledgeEntryDbService.list(
                new LambdaQueryWrapper<KnowledgeEntry>()
                        .eq(KnowledgeEntry::getStatus, 1)
                        .orderByDesc(KnowledgeEntry::getCreateTime));
    }

    // ==================== Embedding ====================

    public void embedEntry(KnowledgeEntry entry) {
        if (vectorStore == null) {
            log.debug("VectorStore unavailable, skipping embed for entry {}", entry.getId());
            return;
        }

        try {
            String text = buildDocumentText(entry);
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("knowledgeEntryId", entry.getId());
            metadata.put("knowledgeType", entry.getKnowledgeType());
            metadata.put("title", entry.getTitle());
            if (entry.getRelatedTables() != null) {
                metadata.put("relatedTables", entry.getRelatedTables());
            }

            Document doc = new Document(UUID.randomUUID().toString(), text, metadata);
            vectorStore.add(List.of(doc));

            entry.setEmbedded(1);
            entry.setUpdateTime(LocalDateTime.now());
            knowledgeEntryDbService.updateById(entry);

            log.debug("Embedded knowledge entry {}: '{}'", entry.getId(), entry.getTitle());
        } catch (Exception e) {
            log.error("Failed to embed knowledge entry {}: {}", entry.getId(), e.getMessage());
        }
    }

    /**
     * Re-embed all non-embedded or all entries.
     */
    public int reEmbedAll(boolean forceAll) {
        if (vectorStore == null) return 0;

        LambdaQueryWrapper<KnowledgeEntry> wrapper = new LambdaQueryWrapper<KnowledgeEntry>()
                .eq(KnowledgeEntry::getStatus, 1);
        if (!forceAll) {
            wrapper.eq(KnowledgeEntry::getEmbedded, 0);
        }
        List<KnowledgeEntry> entries = knowledgeEntryDbService.list(wrapper);

        int count = 0;
        for (KnowledgeEntry entry : entries) {
            if (forceAll) {
                removeFromVectorStore(entry.getId());
            }
            embedEntry(entry);
            count++;
        }
        log.info("Re-embedded {} knowledge entries", count);
        return count;
    }

    // ==================== Semantic Retrieval ====================

    /**
     * Retrieves relevant business knowledge for a SQL statement.
     * Combines:
     * 1. Precise matching by table names
     * 2. Semantic search via vector similarity
     */
    public String retrieveContext(String sqlText, Set<String> tableNames) {
        List<String> contextParts = new ArrayList<>();

        if (tableNames != null && !tableNames.isEmpty()) {
            List<KnowledgeEntry> preciseMatches = findByRelatedTables(tableNames);
            for (KnowledgeEntry entry : preciseMatches) {
                contextParts.add(formatKnowledgeEntry(entry));
            }
        }

        if (vectorStore != null) {
            try {
                List<Document> semanticResults = vectorStore.similaritySearch(
                        SearchRequest.builder()
                                .query(sqlText)
                                .topK(DEFAULT_TOP_K)
                                .similarityThreshold(DEFAULT_SIMILARITY_THRESHOLD)
                                .build());

                Set<Long> alreadyIncluded = contextParts.isEmpty() ? Set.of() :
                        findByRelatedTables(tableNames).stream()
                                .map(KnowledgeEntry::getId)
                                .collect(Collectors.toSet());

                for (Document doc : semanticResults) {
                    Object entryIdObj = doc.getMetadata().get("knowledgeEntryId");
                    if (entryIdObj != null) {
                        Long entryId = Long.valueOf(entryIdObj.toString());
                        if (alreadyIncluded.contains(entryId)) continue;
                    }
                    String title = (String) doc.getMetadata().getOrDefault("title", "");
                    String type = (String) doc.getMetadata().getOrDefault("knowledgeType", "");
                    contextParts.add(String.format("- **[%s] %s**: %s", type, title,
                            truncate(doc.getText(), 500)));
                }
            } catch (Exception e) {
                log.warn("Semantic search failed: {}", e.getMessage());
            }
        }

        if (contextParts.isEmpty()) {
            return "";
        }

        return "## 相关业务知识（RAG 检索）\n\n" + String.join("\n\n", contextParts);
    }

    // ==================== Helpers ====================

    private List<KnowledgeEntry> findByRelatedTables(Set<String> tableNames) {
        List<KnowledgeEntry> allActive = knowledgeEntryDbService.list(
                new LambdaQueryWrapper<KnowledgeEntry>()
                        .eq(KnowledgeEntry::getStatus, 1)
                        .isNotNull(KnowledgeEntry::getRelatedTables));

        return allActive.stream()
                .filter(entry -> {
                    Set<String> entryTables = Arrays.stream(entry.getRelatedTables().split(","))
                            .map(String::trim)
                            .map(String::toLowerCase)
                            .collect(Collectors.toSet());
                    return tableNames.stream()
                            .map(String::toLowerCase)
                            .anyMatch(entryTables::contains);
                })
                .toList();
    }

    private String buildDocumentText(KnowledgeEntry entry) {
        StringBuilder sb = new StringBuilder();
        sb.append("[").append(entry.getKnowledgeType()).append("] ");
        sb.append(entry.getTitle()).append("\n");
        if (entry.getRelatedTables() != null && !entry.getRelatedTables().isBlank()) {
            sb.append("相关表: ").append(entry.getRelatedTables()).append("\n");
        }
        sb.append(entry.getContent());
        return sb.toString();
    }

    private String formatKnowledgeEntry(KnowledgeEntry entry) {
        return String.format("- **[%s] %s** (表: %s): %s",
                entry.getKnowledgeType(),
                entry.getTitle(),
                entry.getRelatedTables(),
                truncate(entry.getContent(), 500));
    }

    private void removeFromVectorStore(Long entryId) {
        if (vectorStore == null || jdbcTemplate == null) return;
        try {
            jdbcTemplate.update(
                    "DELETE FROM knowledge_vector WHERE metadata ->> 'knowledgeEntryId' = ?",
                    String.valueOf(entryId));
        } catch (Exception e) {
            log.warn("Failed to remove entry {} from vector store: {}", entryId, e.getMessage());
        }
    }

    private String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() > maxLen ? text.substring(0, maxLen) + "..." : text;
    }
}

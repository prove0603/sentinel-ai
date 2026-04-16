package com.zhuangjie.sentinel.knowledge;

import com.zhuangjie.sentinel.db.entity.TableMeta;
import com.zhuangjie.sentinel.db.service.TableMetaDbService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 表结构元数据内存缓存。
 * 启动时从 t_table_meta 全量加载，运行时通过 refresh/put/remove 维护一致性。
 * 替代原先从文件系统读取 DDL 的方式，为 AI Prompt 构建提供高性能查询。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TableMetaCacheService {

    private static final Pattern SHARD_SUFFIX_PATTERN =
            Pattern.compile("^(.+?)(_\\d{4,}|_\\d{1,2})$");

    private final TableMetaDbService tableMetaDbService;

    /** tableName(小写) -> TableMeta */
    private final Map<String, TableMeta> cache = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        reload();
    }

    /** 从 DB 全量加载到缓存 */
    public void reload() {
        List<TableMeta> allMetas = tableMetaDbService.list();
        cache.clear();
        for (TableMeta meta : allMetas) {
            cache.put(meta.getTableName().toLowerCase(), meta);
        }
        log.info("[TableMetaCache] 加载完成，共 {} 张表", cache.size());
    }

    /**
     * 根据表名查找元数据，支持分表后缀自动匹配。
     * 如 t_log_202603 → t_log
     */
    public TableMeta get(String tableName) {
        if (tableName == null) return null;
        String lower = tableName.toLowerCase();

        TableMeta meta = cache.get(lower);
        if (meta != null) return meta;

        Matcher m = SHARD_SUFFIX_PATTERN.matcher(lower);
        if (m.matches()) {
            return cache.get(m.group(1));
        }
        return null;
    }

    /** 更新或新增单表缓存 */
    public void put(TableMeta meta) {
        if (meta != null && meta.getTableName() != null) {
            cache.put(meta.getTableName().toLowerCase(), meta);
        }
    }

    /** 移除单表缓存 */
    public void remove(String tableName) {
        if (tableName != null) {
            cache.remove(tableName.toLowerCase());
        }
    }

    public int size() {
        return cache.size();
    }
}

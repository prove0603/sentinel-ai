package com.zhuangjie.sentinel.knowledge;

import lombok.extern.slf4j.Slf4j;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.select.*;
import net.sf.jsqlparser.statement.update.Update;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Extracts all table names referenced in a SQL statement using JSqlParser.
 * Handles SELECT (including JOINs, subqueries), INSERT, UPDATE, DELETE.
 */
@Slf4j
@Component
public class TableNameExtractor {

    /**
     * Extracts all table names from a normalized SQL string.
     * Returns lowercase table names, excluding placeholder names like "_unknown".
     */
    public Set<String> extract(String sql) {
        if (sql == null || sql.isBlank()) {
            return Collections.emptySet();
        }

        Set<String> tables = new LinkedHashSet<>();
        try {
            Statement stmt = CCJSqlParserUtil.parse(sql);
            extractFromStatement(stmt, tables);
        } catch (Exception e) {
            log.debug("Failed to parse SQL for table extraction: {}", e.getMessage());
            extractByRegex(sql, tables);
        }

        tables.removeIf(t -> t.startsWith("_") || t.isBlank());
        return tables;
    }

    private void extractFromStatement(Statement stmt, Set<String> tables) {
        if (stmt instanceof Select select) {
            extractFromSelect(select, tables);
        } else if (stmt instanceof Insert insert) {
            addTable(insert.getTable(), tables);
            if (insert.getSelect() != null) {
                extractFromSelect(insert.getSelect(), tables);
            }
        } else if (stmt instanceof Update update) {
            addTable(update.getTable(), tables);
            if (update.getJoins() != null) {
                for (Join join : update.getJoins()) {
                    extractFromFromItem(join.getRightItem(), tables);
                }
            }
        } else if (stmt instanceof Delete delete) {
            addTable(delete.getTable(), tables);
            if (delete.getJoins() != null) {
                for (Join join : delete.getJoins()) {
                    extractFromFromItem(join.getRightItem(), tables);
                }
            }
        }
    }

    private void extractFromSelect(Select select, Set<String> tables) {
        if (select instanceof PlainSelect plain) {
            extractFromPlainSelect(plain, tables);
        } else if (select instanceof SetOperationList setOp) {
            for (Select s : setOp.getSelects()) {
                extractFromSelect(s, tables);
            }
        }
    }

    private void extractFromPlainSelect(PlainSelect plain, Set<String> tables) {
        if (plain.getFromItem() != null) {
            extractFromFromItem(plain.getFromItem(), tables);
        }
        if (plain.getJoins() != null) {
            for (Join join : plain.getJoins()) {
                extractFromFromItem(join.getRightItem(), tables);
            }
        }
    }

    private void extractFromFromItem(FromItem fromItem, Set<String> tables) {
        if (fromItem instanceof Table table) {
            addTable(table, tables);
        } else if (fromItem instanceof ParenthesedSelect sub) {
            extractFromSelect(sub.getSelect(), tables);
        }
    }

    private void addTable(Table table, Set<String> tables) {
        if (table != null && table.getName() != null) {
            tables.add(table.getName().toLowerCase().replace("`", ""));
        }
    }

    /**
     * Fallback regex extraction when JSqlParser fails (e.g. pseudo-SQL from QueryWrapper).
     */
    private void extractByRegex(String sql, Set<String> tables) {
        String upper = sql.toUpperCase();
        String[] tokens = sql.split("\\s+");
        for (int i = 0; i < tokens.length - 1; i++) {
            String token = tokens[i].toUpperCase();
            if ("FROM".equals(token) || "JOIN".equals(token)
                    || "INTO".equals(token) || "UPDATE".equals(token)) {
                String next = tokens[i + 1].replaceAll("[`,;()]+", "").toLowerCase();
                if (!next.isBlank() && !next.startsWith("(") && !next.startsWith("select")) {
                    tables.add(next);
                }
            }
        }
    }
}

package com.zhuangjie.sentinel.scanner;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.*;
import com.zhuangjie.sentinel.pojo.dto.ScannedSql;
import com.zhuangjie.sentinel.pojo.enums.SqlSourceType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Scans Java source files for MyBatis annotation-based SQL statements.
 * Supports @Select, @Insert, @Update, @Delete annotations with:
 * <ul>
 *   <li>Single string value: {@code @Select("SELECT ...")}</li>
 *   <li>String array value: {@code @Select({"SELECT ...", "FROM ...", "WHERE ..."})}</li>
 *   <li>String concatenation: {@code @Select("SELECT ..." + "FROM ...")}</li>
 * </ul>
 *
 * @see SqlScanner
 */
@Slf4j
@Component
public class AnnotationSqlScanner implements SqlScanner {

    private static final Map<String, String> SQL_ANNOTATIONS = Map.of(
            "Select", "SELECT",
            "Insert", "INSERT",
            "Update", "UPDATE",
            "Delete", "DELETE"
    );

    @Override
    public List<ScannedSql> scan(Path projectRoot) {
        List<Path> javaFiles = JavaFileWalker.findJavaFiles(projectRoot);
        log.info("AnnotationSqlScanner found {} Java files under {}", javaFiles.size(), projectRoot);
        return doScan(projectRoot, javaFiles);
    }

    public List<ScannedSql> scanFiles(Path projectRoot, List<Path> javaFiles) {
        log.info("AnnotationSqlScanner scanning {} specific Java files", javaFiles.size());
        return doScan(projectRoot, javaFiles);
    }

    private List<ScannedSql> doScan(Path projectRoot, List<Path> javaFiles) {
        List<ScannedSql> results = new ArrayList<>();

        for (Path javaFile : javaFiles) {
            if (!Files.exists(javaFile)) {
                continue;
            }
            try {
                CompilationUnit cu = StaticJavaParser.parse(javaFile);
                String relativePath = projectRoot.relativize(javaFile).toString().replace('\\', '/');
                extractAnnotationSql(cu, relativePath, results);
            } catch (Exception e) {
                log.debug("AnnotationSqlScanner skipping file (parse error): {}", javaFile);
            }
        }

        log.info("AnnotationSqlScanner extracted {} SQL statements total", results.size());
        return results;
    }

    private void extractAnnotationSql(CompilationUnit cu, String relativePath, List<ScannedSql> results) {
        cu.findAll(MethodDeclaration.class).forEach(method -> {
            String className = method.findAncestor(ClassOrInterfaceDeclaration.class)
                    .map(ClassOrInterfaceDeclaration::getNameAsString)
                    .orElse("Unknown");

            for (AnnotationExpr ann : method.getAnnotations()) {
                String annName = ann.getNameAsString();
                if (!SQL_ANNOTATIONS.containsKey(annName)) {
                    continue;
                }

                String sql = extractSqlFromAnnotation(ann);
                if (sql == null || sql.isBlank()) {
                    continue;
                }

                String sqlType = SQL_ANNOTATIONS.get(annName);
                String sourceLocation = className + "." + method.getNameAsString();
                String normalized = SqlNormalizer.normalize(sql);

                results.add(new ScannedSql(
                        sql, normalized, sqlType,
                        SqlSourceType.ANNOTATION,
                        relativePath, sourceLocation
                ));
            }
        });
    }

    private String extractSqlFromAnnotation(AnnotationExpr ann) {
        if (ann instanceof SingleMemberAnnotationExpr single) {
            return extractSqlFromExpression(single.getMemberValue());
        }
        if (ann instanceof NormalAnnotationExpr normal) {
            return normal.getPairs().stream()
                    .filter(p -> "value".equals(p.getNameAsString()))
                    .findFirst()
                    .map(p -> extractSqlFromExpression(p.getValue()))
                    .orElse(null);
        }
        return null;
    }

    private String extractSqlFromExpression(Expression expr) {
        if (expr instanceof StringLiteralExpr str) {
            return str.getValue();
        }
        if (expr instanceof TextBlockLiteralExpr textBlock) {
            return textBlock.getValue();
        }
        if (expr instanceof ArrayInitializerExpr array) {
            StringBuilder sb = new StringBuilder();
            for (Expression element : array.getValues()) {
                String part = extractSqlFromExpression(element);
                if (part != null) {
                    if (!sb.isEmpty()) {
                        sb.append(" ");
                    }
                    sb.append(part.strip());
                }
            }
            return sb.toString();
        }
        if (expr instanceof BinaryExpr binary && binary.getOperator() == BinaryExpr.Operator.PLUS) {
            String left = extractSqlFromExpression(binary.getLeft());
            String right = extractSqlFromExpression(binary.getRight());
            return (left != null ? left : "") + (right != null ? right : "");
        }
        return null;
    }
}

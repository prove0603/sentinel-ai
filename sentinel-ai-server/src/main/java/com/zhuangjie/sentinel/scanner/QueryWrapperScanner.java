package com.zhuangjie.sentinel.scanner;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.zhuangjie.sentinel.pojo.dto.ScannedSql;
import com.zhuangjie.sentinel.pojo.enums.SqlSourceType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Scans Java source files for MyBatis-Plus QueryWrapper / LambdaQueryWrapper usage.
 * <p>
 * Detects two main patterns:
 * <ol>
 *   <li><b>Inline chaining</b>: {@code new QueryWrapper<User>().eq("name", val).like("email", val)}</li>
 *   <li><b>Variable-based</b>: variable declaration followed by separate method calls</li>
 * </ol>
 * Builds pseudo-SQL for each detected wrapper to enable rule-based analysis (e.g. full-table scan,
 * leading-wildcard LIKE, deep pagination).
 *
 * @see SqlScanner
 */
@Slf4j
@Component
public class QueryWrapperScanner implements SqlScanner {

    private static final Set<String> WRAPPER_TYPES = Set.of(
            "QueryWrapper", "LambdaQueryWrapper",
            "UpdateWrapper", "LambdaUpdateWrapper"
    );

    private static final Set<String> LAMBDA_WRAPPERS = Set.of(
            "LambdaQueryWrapper", "LambdaUpdateWrapper"
    );

    private static final Set<String> UPDATE_WRAPPERS = Set.of(
            "UpdateWrapper", "LambdaUpdateWrapper"
    );

    private static final Map<String, String> CONDITION_TEMPLATES = Map.ofEntries(
            Map.entry("eq", "%s = ?"),
            Map.entry("ne", "%s <> ?"),
            Map.entry("gt", "%s > ?"),
            Map.entry("ge", "%s >= ?"),
            Map.entry("lt", "%s < ?"),
            Map.entry("le", "%s <= ?"),
            Map.entry("like", "%s LIKE CONCAT('%%', ?, '%%')"),
            Map.entry("likeLeft", "%s LIKE CONCAT('%%', ?)"),
            Map.entry("likeRight", "%s LIKE CONCAT(?, '%%')"),
            Map.entry("notLike", "%s NOT LIKE CONCAT('%%', ?, '%%')"),
            Map.entry("between", "%s BETWEEN ? AND ?"),
            Map.entry("notBetween", "%s NOT BETWEEN ? AND ?"),
            Map.entry("in", "%s IN (?)"),
            Map.entry("notIn", "%s NOT IN (?)"),
            Map.entry("isNull", "%s IS NULL"),
            Map.entry("isNotNull", "%s IS NOT NULL"),
            Map.entry("exists", "EXISTS (?)"),
            Map.entry("notExists", "NOT EXISTS (?)")
    );

    private static final Set<String> ORDER_METHODS = Set.of("orderByAsc", "orderByDesc", "orderBy");
    private static final Set<String> GROUP_METHODS = Set.of("groupBy");

    @Override
    public List<ScannedSql> scan(Path projectRoot) {
        List<Path> javaFiles = JavaFileWalker.findJavaFiles(projectRoot);
        log.info("QueryWrapperScanner found {} Java files under {}", javaFiles.size(), projectRoot);
        return doScan(projectRoot, javaFiles);
    }

    public List<ScannedSql> scanFiles(Path projectRoot, List<Path> javaFiles) {
        log.info("QueryWrapperScanner scanning {} specific Java files", javaFiles.size());
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
                extractWrapperSql(cu, relativePath, results);
            } catch (Exception e) {
                log.debug("QueryWrapperScanner skipping file (parse error): {}", javaFile);
            }
        }

        log.info("QueryWrapperScanner extracted {} pseudo-SQL statements total", results.size());
        return results;
    }

    private void extractWrapperSql(CompilationUnit cu, String relativePath, List<ScannedSql> results) {
        cu.findAll(MethodDeclaration.class).forEach(method -> {
            String className = method.findAncestor(ClassOrInterfaceDeclaration.class)
                    .map(ClassOrInterfaceDeclaration::getNameAsString)
                    .orElse("Unknown");
            String sourceLocation = className + "." + method.getNameAsString();

            List<WrapperUsage> usages = new ArrayList<>();
            collectInlineChains(method, usages);
            collectVariableBasedUsages(method, usages);

            for (WrapperUsage usage : usages) {
                String pseudoSql = buildPseudoSql(usage);
                if (pseudoSql.isBlank()) {
                    continue;
                }

                SqlSourceType sourceType = LAMBDA_WRAPPERS.contains(usage.wrapperType)
                        ? SqlSourceType.LAMBDA_WRAPPER
                        : SqlSourceType.QUERY_WRAPPER;
                String sqlType = UPDATE_WRAPPERS.contains(usage.wrapperType) ? "UPDATE" : "SELECT";
                String normalized = SqlNormalizer.normalize(pseudoSql);

                results.add(new ScannedSql(
                        pseudoSql, normalized, sqlType,
                        sourceType, relativePath, sourceLocation,
                        true
                ));
            }
        });
    }

    // ==================== Inline Chain Detection ====================

    /**
     * Detects inline wrapper chains like:
     * {@code mapper.selectList(new QueryWrapper<User>().eq("name", val).like("email", val))}
     */
    private void collectInlineChains(MethodDeclaration method, List<WrapperUsage> usages) {
        method.findAll(ObjectCreationExpr.class).forEach(creation -> {
            String typeName = extractSimpleTypeName(creation.getType());
            if (!WRAPPER_TYPES.contains(typeName)) {
                return;
            }

            boolean isLambda = LAMBDA_WRAPPERS.contains(typeName);
            WrapperUsage usage = new WrapperUsage(typeName, isLambda);

            Expression current = creation;
            while (current.getParentNode().isPresent()
                    && current.getParentNode().get() instanceof MethodCallExpr parentCall
                    && parentCall.getScope().isPresent()
                    && parentCall.getScope().get() == current) {
                processMethodCall(parentCall, usage);
                current = parentCall;
            }

            if (usage.hasContent()) {
                usages.add(usage);
            }
        });
    }

    // ==================== Variable-Based Detection ====================

    /**
     * Detects variable-based wrapper usage like:
     * <pre>
     * QueryWrapper&lt;User&gt; wrapper = new QueryWrapper&lt;&gt;();
     * wrapper.eq("name", name);
     * wrapper.like("email", email);
     * </pre>
     */
    private void collectVariableBasedUsages(MethodDeclaration method, List<WrapperUsage> usages) {
        Map<String, WrapperUsage> variableMap = new LinkedHashMap<>();

        method.findAll(VariableDeclarator.class).forEach(varDecl -> {
            String typeName = extractTypeFromDeclarator(varDecl);
            if (typeName == null || !WRAPPER_TYPES.contains(typeName)) {
                return;
            }

            if (varDecl.getInitializer().isEmpty()) {
                return;
            }

            Expression init = varDecl.getInitializer().get();
            if (!(init instanceof ObjectCreationExpr)
                    && !isWrappersFactoryCall(init)
                    && !isChainedCreation(init)) {
                return;
            }

            boolean isLambda = LAMBDA_WRAPPERS.contains(typeName);
            variableMap.put(varDecl.getNameAsString(), new WrapperUsage(typeName, isLambda));
        });

        if (variableMap.isEmpty()) {
            return;
        }

        method.findAll(MethodCallExpr.class).forEach(call -> {
            call.getScope().ifPresent(scope -> {
                if (scope instanceof NameExpr nameExpr) {
                    String varName = nameExpr.getNameAsString();
                    WrapperUsage usage = variableMap.get(varName);
                    if (usage != null) {
                        processMethodCall(call, usage);
                    }
                }
            });
        });

        variableMap.values().stream()
                .filter(WrapperUsage::hasContent)
                .forEach(usages::add);
    }

    // ==================== Method Call Processing ====================

    private void processMethodCall(MethodCallExpr call, WrapperUsage usage) {
        String methodName = call.getNameAsString();

        if (CONDITION_TEMPLATES.containsKey(methodName)) {
            processConditionCall(call, methodName, usage);
        } else if ("select".equals(methodName)) {
            processSelectCall(call, usage);
        } else if (ORDER_METHODS.contains(methodName)) {
            processOrderByCall(call, methodName, usage);
        } else if (GROUP_METHODS.contains(methodName)) {
            processGroupByCall(call, usage);
        } else if ("last".equals(methodName)) {
            processLastCall(call, usage);
        } else if ("apply".equals(methodName)) {
            processApplyCall(call, usage);
        } else if ("having".equals(methodName)) {
            processHavingCall(call, usage);
        }
    }

    private void processConditionCall(MethodCallExpr call, String methodName, WrapperUsage usage) {
        String template = CONDITION_TEMPLATES.get(methodName);
        List<Expression> args = call.getArguments();

        boolean hasConditionParam = hasLeadingBooleanParam(args);
        int columnArgIndex = hasConditionParam ? 1 : 0;

        if (columnArgIndex >= args.size()) {
            return;
        }

        String column = extractColumnName(args.get(columnArgIndex), usage.isLambda);
        String condition = String.format(template, column);

        if (hasConditionParam) {
            usage.hasDynamicConditions = true;
        }
        usage.conditions.add(condition);
    }

    private void processSelectCall(MethodCallExpr call, WrapperUsage usage) {
        for (Expression arg : call.getArguments()) {
            String col = extractColumnName(arg, usage.isLambda);
            if (!"_unknown_col".equals(col)) {
                usage.selectColumns.add(col);
            }
        }
    }

    private void processOrderByCall(MethodCallExpr call, String methodName, WrapperUsage usage) {
        String direction = methodName.contains("Desc") ? "DESC" : "ASC";
        for (Expression arg : call.getArguments()) {
            if (arg instanceof BooleanLiteralExpr) {
                continue;
            }
            String col = extractColumnName(arg, usage.isLambda);
            usage.orderByClauses.add(col + " " + direction);
        }
    }

    private void processGroupByCall(MethodCallExpr call, WrapperUsage usage) {
        for (Expression arg : call.getArguments()) {
            String col = extractColumnName(arg, usage.isLambda);
            if (!"_unknown_col".equals(col)) {
                usage.groupByClauses.add(col);
            }
        }
    }

    private void processLastCall(MethodCallExpr call, WrapperUsage usage) {
        if (!call.getArguments().isEmpty()) {
            Expression arg = call.getArgument(0);
            if (arg instanceof StringLiteralExpr str) {
                usage.lastClause = str.getValue();
            }
        }
    }

    private void processApplyCall(MethodCallExpr call, WrapperUsage usage) {
        List<Expression> args = call.getArguments();
        int sqlArgIndex = hasLeadingBooleanParam(args) ? 1 : 0;
        if (sqlArgIndex < args.size() && args.get(sqlArgIndex) instanceof StringLiteralExpr str) {
            String rawCondition = str.getValue().replaceAll("\\{\\d+}", "?");
            usage.conditions.add(rawCondition);
        }
    }

    private void processHavingCall(MethodCallExpr call, WrapperUsage usage) {
        if (!call.getArguments().isEmpty()) {
            Expression arg = call.getArgument(0);
            if (arg instanceof StringLiteralExpr str) {
                usage.havingClause = str.getValue().replaceAll("\\{\\d+}", "?");
            }
        }
    }

    // ==================== Column Name Extraction ====================

    private String extractColumnName(Expression expr, boolean isLambda) {
        if (expr instanceof StringLiteralExpr str) {
            return str.getValue();
        }
        if (expr instanceof MethodReferenceExpr ref) {
            return methodRefToColumnName(ref.getIdentifier());
        }
        return "_unknown_col";
    }

    /**
     * Converts a getter method reference to a snake_case column name.
     * {@code User::getUserName} → {@code user_name}
     * {@code User::isActive} → {@code active}
     */
    static String methodRefToColumnName(String methodName) {
        String field;
        if (methodName.startsWith("get") && methodName.length() > 3) {
            field = methodName.substring(3);
        } else if (methodName.startsWith("is") && methodName.length() > 2) {
            field = methodName.substring(2);
        } else {
            field = methodName;
        }
        return camelToSnakeCase(field);
    }

    static String camelToSnakeCase(String camel) {
        if (camel == null || camel.isEmpty()) {
            return camel;
        }
        String snaked = camel.replaceAll("([a-z0-9])([A-Z])", "$1_$2");
        return snaked.toLowerCase();
    }

    // ==================== Pseudo-SQL Building ====================

    private String buildPseudoSql(WrapperUsage usage) {
        StringBuilder sql = new StringBuilder();
        boolean isUpdate = UPDATE_WRAPPERS.contains(usage.wrapperType);

        if (isUpdate) {
            sql.append("UPDATE _unknown SET _col = ?");
        } else {
            if (!usage.selectColumns.isEmpty()) {
                sql.append("SELECT ").append(String.join(", ", usage.selectColumns));
            } else {
                sql.append("SELECT *");
            }
            sql.append(" FROM _unknown");
        }

        if (!usage.conditions.isEmpty()) {
            sql.append(" WHERE ").append(String.join(" AND ", usage.conditions));
        }

        if (!usage.groupByClauses.isEmpty()) {
            sql.append(" GROUP BY ").append(String.join(", ", usage.groupByClauses));
        }

        if (usage.havingClause != null) {
            sql.append(" HAVING ").append(usage.havingClause);
        }

        if (!usage.orderByClauses.isEmpty()) {
            sql.append(" ORDER BY ").append(String.join(", ", usage.orderByClauses));
        }

        if (usage.lastClause != null) {
            sql.append(" ").append(usage.lastClause);
        }

        return sql.toString();
    }

    // ==================== Utility Methods ====================

    private boolean hasLeadingBooleanParam(List<Expression> args) {
        if (args.isEmpty()) {
            return false;
        }
        Expression first = args.get(0);
        return first instanceof BooleanLiteralExpr
                || first instanceof NameExpr
                || first instanceof MethodCallExpr;
    }

    private String extractSimpleTypeName(ClassOrInterfaceType type) {
        return type.getNameAsString();
    }

    private String extractTypeFromDeclarator(VariableDeclarator varDecl) {
        if (varDecl.getType() instanceof ClassOrInterfaceType classType) {
            return classType.getNameAsString();
        }
        return null;
    }

    private boolean isWrappersFactoryCall(Expression expr) {
        if (expr instanceof MethodCallExpr call) {
            String name = call.getNameAsString();
            return "lambdaQuery".equals(name) || "lambdaUpdate".equals(name)
                    || "query".equals(name) || "update".equals(name);
        }
        return false;
    }

    private boolean isChainedCreation(Expression expr) {
        if (!(expr instanceof MethodCallExpr call)) {
            return false;
        }
        return call.getScope()
                .map(scope -> scope instanceof ObjectCreationExpr creation
                        && WRAPPER_TYPES.contains(extractSimpleTypeName(creation.getType())))
                .orElse(false);
    }

    // ==================== Inner Class ====================

    private static class WrapperUsage {
        final String wrapperType;
        final boolean isLambda;
        final List<String> conditions = new ArrayList<>();
        final List<String> selectColumns = new ArrayList<>();
        final List<String> orderByClauses = new ArrayList<>();
        final List<String> groupByClauses = new ArrayList<>();
        String lastClause;
        String havingClause;
        boolean hasDynamicConditions;

        WrapperUsage(String wrapperType, boolean isLambda) {
            this.wrapperType = wrapperType;
            this.isLambda = isLambda;
        }

        boolean hasContent() {
            return !conditions.isEmpty() || !selectColumns.isEmpty()
                    || !orderByClauses.isEmpty() || !groupByClauses.isEmpty()
                    || lastClause != null || havingClause != null;
        }
    }
}

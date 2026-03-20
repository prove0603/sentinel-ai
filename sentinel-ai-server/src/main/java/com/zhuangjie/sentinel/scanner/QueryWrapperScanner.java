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

    /**
     * Scans a raw Java code snippet (not a file) for QueryWrapper/LambdaQueryWrapper usage.
     * Used for testing and debugging pseudo-SQL generation.
     */
    public List<ScannedSql> scanCodeSnippet(String javaCode) {
        List<ScannedSql> results = new ArrayList<>();
        try {
            CompilationUnit cu = StaticJavaParser.parse(javaCode);
            Map<String, String> tableNameMap = buildTableNameMap(cu);
            extractWrapperSql(cu, "snippet", results, tableNameMap);
        } catch (Exception e) {
            log.error("Failed to parse code snippet", e);
        }
        return results;
    }

    private List<ScannedSql> doScan(Path projectRoot, List<Path> javaFiles) {
        List<ScannedSql> results = new ArrayList<>();
        Map<String, String> tableNameMap = buildTableNameMapFromFiles(projectRoot, javaFiles);

        for (Path javaFile : javaFiles) {
            if (!Files.exists(javaFile)) {
                continue;
            }
            try {
                CompilationUnit cu = StaticJavaParser.parse(javaFile);
                String relativePath = projectRoot.relativize(javaFile).toString().replace('\\', '/');
                extractWrapperSql(cu, relativePath, results, tableNameMap);
            } catch (Exception e) {
                log.debug("QueryWrapperScanner skipping file (parse error): {}", javaFile);
            }
        }

        log.info("QueryWrapperScanner extracted {} pseudo-SQL statements total", results.size());
        return results;
    }

    private void extractWrapperSql(CompilationUnit cu, String relativePath, List<ScannedSql> results,
                                    Map<String, String> tableNameMap) {
        cu.findAll(MethodDeclaration.class).forEach(method -> {
            String className = method.findAncestor(ClassOrInterfaceDeclaration.class)
                    .map(ClassOrInterfaceDeclaration::getNameAsString)
                    .orElse("Unknown");
            String sourceLocation = className + "." + method.getNameAsString();

            List<WrapperUsage> usages = new ArrayList<>();
            collectInlineChains(method, usages);
            collectVariableBasedUsages(method, usages);

            for (WrapperUsage usage : usages) {
                String pseudoSql = buildPseudoSql(usage, tableNameMap);
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
            usage.entityName = extractGenericTypeName(creation.getType());

            // Diamond operator fallback: try to resolve from variable declaration type
            if (usage.entityName == null) {
                usage.entityName = resolveEntityFromContext(creation);
            }

            Expression current = creation;
            while (current.getParentNode().isPresent()
                    && current.getParentNode().get() instanceof MethodCallExpr parentCall
                    && parentCall.getScope().isPresent()
                    && parentCall.getScope().get() == current) {
                processMethodCall(parentCall, usage);
                // Try to infer entity name from lambda method reference scope (e.g. User::getName → User)
                if (usage.entityName == null && isLambda) {
                    usage.entityName = inferEntityFromMethodRef(parentCall);
                }
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
            WrapperUsage wu = new WrapperUsage(typeName, isLambda);
            // Extract entity type from generic: QueryWrapper<Queue> → Queue
            if (varDecl.getType() instanceof ClassOrInterfaceType classType) {
                wu.entityName = extractGenericTypeName(classType);
            }
            variableMap.put(varDecl.getNameAsString(), wu);
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
                        if (usage.entityName == null && usage.isLambda) {
                            usage.entityName = inferEntityFromMethodRef(call);
                        }
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
        } else if ("set".equals(methodName)) {
            processSetCall(call, usage);
        } else if ("setSql".equals(methodName)) {
            processSetSqlCall(call, usage);
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

        Expression columnArg = args.get(columnArgIndex);
        String column = extractColumnName(columnArg, usage.isLambda);
        tryInferEntityFromColumnArg(columnArg, usage);
        String condition = String.format(template, column);

        if (hasConditionParam) {
            usage.hasDynamicConditions = true;
        }
        usage.conditions.add(condition);
    }

    private void processSelectCall(MethodCallExpr call, WrapperUsage usage) {
        for (Expression arg : call.getArguments()) {
            String col = extractColumnName(arg, usage.isLambda);
            tryInferEntityFromColumnArg(arg, usage);
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
            tryInferEntityFromColumnArg(arg, usage);
            usage.orderByClauses.add(col + " " + direction);
        }
    }

    private void processGroupByCall(MethodCallExpr call, WrapperUsage usage) {
        for (Expression arg : call.getArguments()) {
            String col = extractColumnName(arg, usage.isLambda);
            tryInferEntityFromColumnArg(arg, usage);
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

    /**
     * Handles UpdateWrapper.set(column, value) and set(condition, column, value).
     */
    private void processSetCall(MethodCallExpr call, WrapperUsage usage) {
        List<Expression> args = call.getArguments();
        boolean hasConditionParam = hasLeadingBooleanParam(args);
        int columnArgIndex = hasConditionParam ? 1 : 0;
        if (columnArgIndex >= args.size()) {
            return;
        }
        Expression columnArg = args.get(columnArgIndex);
        String column = extractColumnName(columnArg, usage.isLambda);
        tryInferEntityFromColumnArg(columnArg, usage);
        usage.setClauses.add(column + " = ?");
        if (hasConditionParam) {
            usage.hasDynamicConditions = true;
        }
    }

    /**
     * Handles UpdateWrapper.setSql(rawSql) and setSql(condition, rawSql).
     */
    private void processSetSqlCall(MethodCallExpr call, WrapperUsage usage) {
        List<Expression> args = call.getArguments();
        int sqlArgIndex = hasLeadingBooleanParam(args) ? 1 : 0;
        if (sqlArgIndex < args.size() && args.get(sqlArgIndex) instanceof StringLiteralExpr str) {
            usage.setClauses.add(str.getValue().replaceAll("\\{\\d+}", "?"));
        }
    }

    // ==================== Entity Inference ====================

    /**
     * Infers entityName from column argument when generic type is unavailable (raw QueryWrapper).
     * <p>Strategies:
     * <ol>
     *   <li>FieldAccessExpr: {@code wrapper.eq(ServiceChannelConfig.SERVICE_CHANNEL_ID, value)} → "ServiceChannelConfig"</li>
     *   <li>NameExpr with static import: {@code wrapper.eq(NAME, value)} where NAME is statically imported from UserCollector → "UserCollector"</li>
     * </ol>
     */
    private void tryInferEntityFromColumnArg(Expression expr, WrapperUsage usage) {
        if (usage.entityName != null) {
            return;
        }
        if (expr instanceof FieldAccessExpr fieldAccess) {
            Expression scope = fieldAccess.getScope();
            if (scope instanceof NameExpr nameExpr) {
                String className = nameExpr.getNameAsString();
                if (Character.isUpperCase(className.charAt(0)) && !COMMON_UTILITY_CLASSES.contains(className)) {
                    usage.entityName = className;
                }
            }
        }
    }

    private static final Set<String> COMMON_UTILITY_CLASSES = Set.of(
            "Objects", "Collections", "Arrays", "Math", "String",
            "System", "Integer", "Long", "Boolean", "Double", "Float",
            "Optional", "Stream", "Collectors", "List", "Map", "Set",
            "StringUtils", "CollectionUtils", "ObjectUtils", "BeanUtils"
    );

    // ==================== Column Name Extraction ====================

    private String extractColumnName(Expression expr, boolean isLambda) {
        if (expr instanceof StringLiteralExpr str) {
            return str.getValue();
        }
        if (expr instanceof MethodReferenceExpr ref) {
            return methodRefToColumnName(ref.getIdentifier());
        }
        // Handle constant references like Queue.COUNTRY_ID or Queue.queueCode
        if (expr instanceof FieldAccessExpr fieldAccess) {
            return resolveFieldName(fieldAccess.getNameAsString());
        }
        // Handle simple variable references like COLUMN_NAME
        if (expr instanceof NameExpr nameExpr) {
            String name = nameExpr.getNameAsString();
            return resolveFieldName(name);
        }
        return "_unknown_col";
    }

    /**
     * Resolves a Java field name (constant or variable) to a DB column name.
     * UPPER_SNAKE_CASE (e.g. COUNTRY_ID) → lower (country_id)
     * UPPER (e.g. ID) → lower (id)
     * camelCase (e.g. countryId) → snake_case (country_id)
     * already_snake (e.g. country_id) → as-is
     */
    static String resolveFieldName(String name) {
        if (name == null || name.isEmpty()) return "_unknown_col";
        // All uppercase: COUNTRY_ID → country_id, ID → id
        if (name.equals(name.toUpperCase())) {
            return name.toLowerCase();
        }
        // Already lowercase snake_case
        if (name.equals(name.toLowerCase()) && name.contains("_")) {
            return name;
        }
        // CamelCase → snake_case
        return camelToSnakeCase(name);
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

    private String buildPseudoSql(WrapperUsage usage, Map<String, String> tableNameMap) {
        StringBuilder sql = new StringBuilder();
        boolean isUpdate = UPDATE_WRAPPERS.contains(usage.wrapperType);
        String tableName = resolveTableName(usage.entityName, tableNameMap);

        if (isUpdate) {
            sql.append("UPDATE ").append(tableName).append(" SET ");
            if (!usage.setClauses.isEmpty()) {
                sql.append(String.join(", ", usage.setClauses));
            } else {
                sql.append("_col = ?");
            }
        } else {
            if (!usage.selectColumns.isEmpty()) {
                sql.append("SELECT ").append(String.join(", ", usage.selectColumns));
            } else {
                sql.append("SELECT *");
            }
            sql.append(" FROM ").append(tableName);
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

    /**
     * Detects if the first argument is a boolean condition parameter.
     * MyBatis-Plus overloads: eq(boolean condition, R column, Object val) vs eq(R column, Object val).
     * Strategy: determine by argument count — condition methods expect 3 args with bool prefix, 2 without.
     * Also checks expression type heuristics for ambiguous cases.
     */
    private boolean hasLeadingBooleanParam(List<Expression> args) {
        if (args.size() < 2) {
            return false;
        }
        Expression first = args.get(0);
        if (first instanceof BooleanLiteralExpr) {
            return true;
        }
        // Binary expressions like (x != null), (x > 0) are boolean conditions
        if (first instanceof BinaryExpr) {
            return true;
        }
        // Unary expression like !flag
        if (first instanceof UnaryExpr) {
            return true;
        }
        // Enclosed expression like (condition)
        if (first instanceof EnclosedExpr) {
            return true;
        }
        // For MethodCallExpr and NameExpr, use arg count as heuristic:
        // 3+ args and first is MethodCallExpr/NameExpr → likely boolean prefix
        if (args.size() >= 3 && (first instanceof MethodCallExpr || first instanceof NameExpr)) {
            return true;
        }
        return false;
    }

    private String extractSimpleTypeName(ClassOrInterfaceType type) {
        return type.getNameAsString();
    }

    /**
     * Extracts the generic type parameter name from QueryWrapper&lt;Queue&gt; → "Queue".
     */
    private String extractGenericTypeName(ClassOrInterfaceType type) {
        return type.getTypeArguments()
                .filter(args -> !args.isEmpty())
                .map(args -> args.get(0))
                .filter(ClassOrInterfaceType.class::isInstance)
                .map(t -> ((ClassOrInterfaceType) t).getNameAsString())
                .orElse(null);
    }

    /**
     * Resolves entity class name to table name.
     * Priority: @TableName annotation mapping > "t_" + snake_case convention.
     */
    private String resolveTableName(String entityName, Map<String, String> tableNameMap) {
        if (entityName == null || entityName.isEmpty()) {
            return "_unknown";
        }
        if (tableNameMap != null && tableNameMap.containsKey(entityName)) {
            return tableNameMap.get(entityName);
        }
        String snake = camelToSnakeCase(entityName);
        return "t_" + snake;
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

    // ==================== Entity & Table Name Resolution ====================

    /**
     * Infers entity name from a lambda method reference argument in a wrapper chain call.
     * E.g., {@code .eq(User::getName, val)} → scope "User" is the entity.
     */
    private String inferEntityFromMethodRef(MethodCallExpr call) {
        for (Expression arg : call.getArguments()) {
            if (arg instanceof MethodReferenceExpr ref) {
                Expression scope = ref.getScope();
                if (scope instanceof TypeExpr typeExpr
                        && typeExpr.getType() instanceof ClassOrInterfaceType classType) {
                    return classType.getNameAsString();
                }
                if (scope instanceof NameExpr nameExpr) {
                    return nameExpr.getNameAsString();
                }
            }
        }
        return null;
    }

    /**
     * Resolves entity name from the context when diamond operator is used.
     * E.g., {@code QueryWrapper<CaseBill> wrapper = new QueryWrapper<>()} → "CaseBill"
     * Also handles: {@code mapper.selectList(new QueryWrapper<>()...)} by checking mapper's generic type.
     */
    private String resolveEntityFromContext(ObjectCreationExpr creation) {
        // Case 1: variable declaration — QueryWrapper<Entity> w = new QueryWrapper<>()
        if (creation.getParentNode().isPresent()
                && creation.getParentNode().get() instanceof VariableDeclarator varDecl) {
            if (varDecl.getType() instanceof ClassOrInterfaceType classType) {
                return extractGenericTypeName(classType);
            }
        }
        // Case 2: assignment — w = new QueryWrapper<>() where w was declared with type
        if (creation.getParentNode().isPresent()
                && creation.getParentNode().get() instanceof AssignExpr) {
            // Hard to resolve without symbol table; skip
            return null;
        }
        return null;
    }

    /**
     * Builds entity-name → table-name mapping from @TableName annotations in a compilation unit.
     */
    private Map<String, String> buildTableNameMap(CompilationUnit cu) {
        Map<String, String> map = new HashMap<>();
        cu.findAll(ClassOrInterfaceDeclaration.class).forEach(clazz ->
                extractTableNameAnnotation(clazz, map));
        return map;
    }

    /**
     * Builds entity-name → table-name mapping by scanning all Java files for @TableName annotations.
     */
    private Map<String, String> buildTableNameMapFromFiles(Path projectRoot, List<Path> javaFiles) {
        Map<String, String> map = new HashMap<>();
        for (Path javaFile : javaFiles) {
            if (!Files.exists(javaFile)) continue;
            try {
                CompilationUnit cu = StaticJavaParser.parse(javaFile);
                cu.findAll(ClassOrInterfaceDeclaration.class).forEach(clazz ->
                        extractTableNameAnnotation(clazz, map));
            } catch (Exception e) {
                // skip parse errors
            }
        }
        log.info("QueryWrapperScanner resolved {} @TableName mappings", map.size());
        return map;
    }

    private void extractTableNameAnnotation(ClassOrInterfaceDeclaration clazz, Map<String, String> map) {
        clazz.getAnnotationByName("TableName").ifPresent(ann -> {
            String tableName = null;
            if (ann instanceof SingleMemberAnnotationExpr singleAnn
                    && singleAnn.getMemberValue() instanceof StringLiteralExpr str) {
                tableName = str.getValue();
            } else if (ann instanceof NormalAnnotationExpr normalAnn) {
                tableName = normalAnn.getPairs().stream()
                        .filter(p -> "value".equals(p.getNameAsString()))
                        .findFirst()
                        .filter(p -> p.getValue() instanceof StringLiteralExpr)
                        .map(p -> ((StringLiteralExpr) p.getValue()).getValue())
                        .orElse(null);
            }
            if (tableName != null && !tableName.isEmpty()) {
                map.put(clazz.getNameAsString(), tableName);
            }
        });
    }

    // ==================== Inner Class ====================

    private static class WrapperUsage {
        final String wrapperType;
        final boolean isLambda;
        final List<String> conditions = new ArrayList<>();
        final List<String> selectColumns = new ArrayList<>();
        final List<String> orderByClauses = new ArrayList<>();
        final List<String> groupByClauses = new ArrayList<>();
        final List<String> setClauses = new ArrayList<>();
        String lastClause;
        String havingClause;
        boolean hasDynamicConditions;
        String entityName;

        WrapperUsage(String wrapperType, boolean isLambda) {
            this.wrapperType = wrapperType;
            this.isLambda = isLambda;
        }

        boolean hasContent() {
            return !conditions.isEmpty() || !selectColumns.isEmpty()
                    || !orderByClauses.isEmpty() || !groupByClauses.isEmpty()
                    || !setClauses.isEmpty()
                    || lastClause != null || havingClause != null;
        }
    }
}

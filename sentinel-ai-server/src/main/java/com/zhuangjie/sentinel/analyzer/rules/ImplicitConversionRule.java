package com.zhuangjie.sentinel.analyzer.rules;

import com.zhuangjie.sentinel.pojo.dto.ScannedSql;
import com.zhuangjie.sentinel.pojo.enums.RiskLevel;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.expression.operators.conditional.OrExpression;
import net.sf.jsqlparser.expression.operators.relational.ComparisonOperator;
import net.sf.jsqlparser.expression.operators.relational.ParenthesedExpressionList;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.update.Update;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Detects function wrapping on columns in WHERE clause conditions,
 * which causes implicit conversion and prevents index usage.
 * <p>
 * Examples: WHERE YEAR(create_time) = 2024, WHERE DATE_FORMAT(dt, '%Y-%m') = '2024-01'
 * <p>
 * Note: Full implicit type conversion detection requires table metadata (P2+).
 */
public record ImplicitConversionRule() implements SqlRule {

    private static final Set<String> RISKY_FUNCTIONS = Set.of(
            "YEAR", "MONTH", "DAY", "DATE", "DATE_FORMAT", "DATEDIFF",
            "CAST", "CONVERT", "IFNULL", "COALESCE", "NVL",
            "UPPER", "LOWER", "TRIM", "LTRIM", "RTRIM",
            "SUBSTR", "SUBSTRING", "LEFT", "RIGHT",
            "ABS", "CEIL", "FLOOR", "ROUND"
    );

    @Override
    public String name() {
        return "ImplicitConversion";
    }

    @Override
    public String description() {
        return "检测 WHERE 条件中对列使用函数包裹，导致索引失效";
    }

    @Override
    public List<RuleViolation> analyze(ScannedSql scannedSql, Statement parsedStatement) {
        if (parsedStatement == null) {
            return List.of();
        }

        Expression where = extractWhere(parsedStatement);
        if (where == null) {
            return List.of();
        }

        List<RuleViolation> violations = new ArrayList<>();
        collectFunctionOnColumnViolations(where, violations);
        return violations;
    }

    private Expression extractWhere(Statement statement) {
        return switch (statement) {
            case PlainSelect ps -> ps.getWhere();
            case Update upd -> upd.getWhere();
            case Delete del -> del.getWhere();
            default -> null;
        };
    }

    private void collectFunctionOnColumnViolations(Expression expr, List<RuleViolation> violations) {
        if (expr instanceof ComparisonOperator comp) {
            checkSide(comp.getLeftExpression(), violations);
            checkSide(comp.getRightExpression(), violations);
        } else if (expr instanceof AndExpression and) {
            collectFunctionOnColumnViolations(and.getLeftExpression(), violations);
            collectFunctionOnColumnViolations(and.getRightExpression(), violations);
        } else if (expr instanceof OrExpression or) {
            collectFunctionOnColumnViolations(or.getLeftExpression(), violations);
            collectFunctionOnColumnViolations(or.getRightExpression(), violations);
        } else if (expr instanceof ParenthesedExpressionList<?> paren) {
            paren.forEach(e -> collectFunctionOnColumnViolations(e, violations));
        }
    }

    private void checkSide(Expression expr, List<RuleViolation> violations) {
        if (expr instanceof Function func) {
            String funcName = func.getName().toUpperCase();
            if (RISKY_FUNCTIONS.contains(funcName) && containsColumn(func)) {
                violations.add(new RuleViolation(
                        name(), RiskLevel.P2,
                        "对列使用 " + funcName + "() 函数，导致索引失效",
                        "表达式: " + func + "，建议将函数运算移到等号右侧或使用等价改写"
                ));
            }
        }
    }

    private boolean containsColumn(Function func) {
        if (func.getParameters() == null) {
            return false;
        }
        return func.getParameters().getExpressions().stream()
                .anyMatch(e -> e instanceof Column);
    }
}

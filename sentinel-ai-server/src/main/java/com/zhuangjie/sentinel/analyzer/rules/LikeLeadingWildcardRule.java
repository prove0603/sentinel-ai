package com.zhuangjie.sentinel.analyzer.rules;

import com.zhuangjie.sentinel.pojo.dto.ScannedSql;
import com.zhuangjie.sentinel.pojo.enums.RiskLevel;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.expression.StringValue;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.expression.operators.conditional.OrExpression;
import net.sf.jsqlparser.expression.operators.relational.LikeExpression;
import net.sf.jsqlparser.expression.operators.relational.ParenthesedExpressionList;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.update.Update;

import java.util.ArrayList;
import java.util.List;

/**
 * Detects LIKE expressions with leading wildcards (e.g. LIKE '%xxx'),
 * which prevent index usage on the column.
 */
public record LikeLeadingWildcardRule() implements SqlRule {

    @Override
    public String name() {
        return "LikeLeadingWildcard";
    }

    @Override
    public String description() {
        return "检测 LIKE 左模糊查询（以 % 开头），无法使用索引";
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

        List<LikeExpression> likes = new ArrayList<>();
        collectLikeExpressions(where, likes);

        List<RuleViolation> violations = new ArrayList<>();
        for (LikeExpression like : likes) {
            if (isLeadingWildcard(like)) {
                String column = like.getLeftExpression().toString();
                violations.add(new RuleViolation(
                        name(), RiskLevel.P1,
                        "LIKE 左模糊查询无法使用索引: " + column,
                        "表达式: " + like + "，建议使用全文索引或改为右模糊查询"
                ));
            }
        }
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

    private void collectLikeExpressions(Expression expr, List<LikeExpression> result) {
        if (expr instanceof LikeExpression like) {
            result.add(like);
        } else if (expr instanceof AndExpression and) {
            collectLikeExpressions(and.getLeftExpression(), result);
            collectLikeExpressions(and.getRightExpression(), result);
        } else if (expr instanceof OrExpression or) {
            collectLikeExpressions(or.getLeftExpression(), result);
            collectLikeExpressions(or.getRightExpression(), result);
        } else if (expr instanceof ParenthesedExpressionList<?> paren) {
            paren.forEach(e -> collectLikeExpressions(e, result));
        }
    }

    private boolean isLeadingWildcard(LikeExpression like) {
        Expression right = like.getRightExpression();

        if (right instanceof StringValue sv) {
            return sv.getValue().startsWith("%");
        }

        if (right instanceof Function func) {
            String funcName = func.getName();
            if ("CONCAT".equalsIgnoreCase(funcName) && func.getParameters() != null) {
                var params = func.getParameters().getExpressions();
                if (!params.isEmpty() && params.get(0) instanceof StringValue sv) {
                    return sv.getValue().startsWith("%");
                }
            }
        }

        return false;
    }
}

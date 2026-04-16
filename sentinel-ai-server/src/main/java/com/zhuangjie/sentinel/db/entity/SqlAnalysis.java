package com.zhuangjie.sentinel.db.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("t_sql_analysis")
public class SqlAnalysis {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long sqlRecordId;

    private Long scanBatchId;

    /** P0 / P1 / P2 / P3 / P4 */
    private String ruleRiskLevel;

    /** JSON - issues found by rule engine */
    private String ruleIssues;

    /** P0 / P1 / P2 / P3 / P4 */
    private String aiRiskLevel;

    private String aiAnalysis;

    private String aiIndexSuggestion;

    private String aiRewriteSuggestion;

    private Long aiEstimatedScanRows;

    private Integer aiEstimatedExecTimeMs;

    private BigDecimal aiConfidence;

    private String aiModel;

    private Integer aiTokensUsed;

    /** Combined risk level from rule + AI */
    private String finalRiskLevel;

    /** PENDING / CONFIRMED / FIXED / IGNORED / FALSE_POSITIVE */
    private String handleStatus;

    private String handleNote;

    private String handler;

    private LocalDateTime handleTime;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(exist = false)
    private String owner;
}

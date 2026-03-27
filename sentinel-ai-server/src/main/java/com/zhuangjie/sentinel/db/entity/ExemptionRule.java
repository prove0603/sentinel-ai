package com.zhuangjie.sentinel.db.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("t_exemption_rule")
public class ExemptionRule {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long projectId;

    /** SOURCE_CLASS or TABLE_NAME */
    private String ruleType;

    private String pattern;

    private String reason;

    /** 0-disabled 1-enabled */
    private Integer status;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}

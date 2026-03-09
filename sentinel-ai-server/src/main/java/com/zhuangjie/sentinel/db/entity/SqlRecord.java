package com.zhuangjie.sentinel.db.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("t_sql_record")
public class SqlRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long projectId;

    private String sqlHash;

    private String sqlText;

    private String sqlNormalized;

    /** SELECT / INSERT / UPDATE / DELETE */
    private String sqlType;

    /** MAPPER_XML / QUERY_WRAPPER / LAMBDA_WRAPPER / ANNOTATION */
    private String sourceType;

    private String sourceFile;

    /** namespace.id or ClassName.methodName */
    private String sourceLocation;

    private Long firstScanId;

    private Long lastScanId;

    /** 0-deleted 1-active */
    private Integer status;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}

package com.zhuangjie.sentinel.db.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("t_scan_batch")
public class ScanBatch {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long projectId;

    /** FULL / INCREMENTAL */
    private String scanType;

    private String fromCommit;

    private String toCommit;

    private Integer totalSqlCount;

    private Integer newSqlCount;

    private Integer changedSqlCount;

    private Integer removedSqlCount;

    private Integer riskSqlCount;

    private Long scanDurationMs;

    /** RUNNING / COMPLETED / FAILED */
    private String status;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}

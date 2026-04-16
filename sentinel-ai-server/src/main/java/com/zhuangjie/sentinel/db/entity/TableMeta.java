package com.zhuangjie.sentinel.db.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("t_table_meta")
public class TableMeta {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long projectId;

    private String tableName;

    private String ddlText;

    private Long estimatedRows;

    private Integer avgRowLength;

    /** JSON 格式的索引信息（兼容旧字段） */
    private String indexInfo;

    /** 文本格式的索引统计（从 DBA 平台采集） */
    private String indexStats;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}

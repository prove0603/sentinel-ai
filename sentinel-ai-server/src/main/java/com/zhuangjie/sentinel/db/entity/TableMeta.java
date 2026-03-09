package com.zhuangjie.sentinel.db.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("t_table_meta")
public class TableMeta {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String tableName;

    private String ddlText;

    private Long estimatedRows;

    private Integer avgRowLength;

    /** JSON format index info */
    private String indexInfo;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}

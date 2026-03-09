package com.zhuangjie.sentinel.db.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("t_project_config")
public class ProjectConfig {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String projectName;

    private String gitRepoPath;

    private String lastScanCommit;

    private LocalDateTime lastScanTime;

    /** MANUAL / DB_CONNECT */
    private String tableSchemaSource;

    private String dbConnectionUrl;

    /** 0-disabled 1-enabled */
    private Integer status;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}

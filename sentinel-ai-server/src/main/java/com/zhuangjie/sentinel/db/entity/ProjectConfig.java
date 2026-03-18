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

    /** Local git repo path (legacy, used when gitRemoteUrl is null) */
    private String gitRepoPath;

    /** Remote git URL (SSH or HTTPS), e.g. git@gitlab.com:org/repo.git */
    private String gitRemoteUrl;

    /** Git branch to scan (default: master) */
    private String gitBranch;

    /** Webhook secret for verifying push events */
    private String webhookSecret;

    /** Project path on Git platform: GitLab "group/repo", GitHub "owner/repo" */
    private String gitProjectPath;

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

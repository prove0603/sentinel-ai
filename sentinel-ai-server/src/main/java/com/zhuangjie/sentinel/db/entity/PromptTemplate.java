package com.zhuangjie.sentinel.db.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Prompt 模板实体。存储可配置的 AI 提示词模板。
 */
@Data
@TableName("t_prompt_template")
public class PromptTemplate {

    @TableId(type = IdType.AUTO)
    private Long id;
    /** 模板唯一标识（如 SQL_ANALYSIS_SYSTEM, SQL_ANALYSIS_USER） */
    private String templateKey;
    /** 模板显示名称 */
    private String templateName;
    /** 模板内容（prompt 正文） */
    private String content;
    /** 模板说明 */
    private String description;
    /** 状态：1=启用, 0=禁用 */
    private Integer status;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}

package com.zhuangjie.sentinel.db.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("t_knowledge_entry")
public class KnowledgeEntry {

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * Knowledge type: EXEMPTION, BACKGROUND, EXPERIENCE, QPS_INFO, SLOW_QUERY_NOTE
     */
    private String knowledgeType;

    private String title;

    private String content;

    /**
     * Comma-separated related table names for precise matching
     */
    private String relatedTables;

    /**
     * Knowledge source: MANUAL, IMPORT, AUTO
     */
    private String source;

    /**
     * Whether this entry has been embedded into vector store
     */
    private Integer embedded;

    private Integer status;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}

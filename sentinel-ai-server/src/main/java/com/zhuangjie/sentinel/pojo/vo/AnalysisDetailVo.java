package com.zhuangjie.sentinel.pojo.vo;

import com.zhuangjie.sentinel.db.entity.SqlAnalysis;
import lombok.Data;

@Data
public class AnalysisDetailVo {

    private SqlAnalysis analysis;

    private String sqlText;

    private String sqlNormalized;

    private String sqlType;

    private String sourceType;

    private String sourceFile;

    private String sourceLocation;

    /** DDL content read from table-meta files for the tables referenced in this SQL */
    private String tableMetaContext;
}

package com.zhuangjie.sentinel.db.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zhuangjie.sentinel.db.entity.SqlAnalysis;
import com.zhuangjie.sentinel.db.mapper.SqlAnalysisMapper;
import org.springframework.stereotype.Service;

@Service
public class SqlAnalysisDbService extends ServiceImpl<SqlAnalysisMapper, SqlAnalysis> {
}

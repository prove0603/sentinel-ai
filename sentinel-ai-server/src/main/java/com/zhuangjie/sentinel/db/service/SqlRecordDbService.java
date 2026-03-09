package com.zhuangjie.sentinel.db.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zhuangjie.sentinel.db.entity.SqlRecord;
import com.zhuangjie.sentinel.db.mapper.SqlRecordMapper;
import org.springframework.stereotype.Service;

@Service
public class SqlRecordDbService extends ServiceImpl<SqlRecordMapper, SqlRecord> {
}

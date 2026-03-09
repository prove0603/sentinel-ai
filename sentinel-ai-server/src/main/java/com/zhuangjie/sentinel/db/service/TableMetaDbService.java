package com.zhuangjie.sentinel.db.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zhuangjie.sentinel.db.entity.TableMeta;
import com.zhuangjie.sentinel.db.mapper.TableMetaMapper;
import org.springframework.stereotype.Service;

@Service
public class TableMetaDbService extends ServiceImpl<TableMetaMapper, TableMeta> {
}

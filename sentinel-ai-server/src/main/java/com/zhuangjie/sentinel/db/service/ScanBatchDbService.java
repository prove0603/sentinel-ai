package com.zhuangjie.sentinel.db.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zhuangjie.sentinel.db.entity.ScanBatch;
import com.zhuangjie.sentinel.db.mapper.ScanBatchMapper;
import org.springframework.stereotype.Service;

@Service
public class ScanBatchDbService extends ServiceImpl<ScanBatchMapper, ScanBatch> {
}

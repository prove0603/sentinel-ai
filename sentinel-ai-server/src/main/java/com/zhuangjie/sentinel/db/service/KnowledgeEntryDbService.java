package com.zhuangjie.sentinel.db.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zhuangjie.sentinel.db.entity.KnowledgeEntry;
import com.zhuangjie.sentinel.db.mapper.KnowledgeEntryMapper;
import org.springframework.stereotype.Service;

@Service
public class KnowledgeEntryDbService extends ServiceImpl<KnowledgeEntryMapper, KnowledgeEntry> {
}

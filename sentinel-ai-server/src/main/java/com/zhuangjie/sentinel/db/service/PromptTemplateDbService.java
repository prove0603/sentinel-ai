package com.zhuangjie.sentinel.db.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zhuangjie.sentinel.db.entity.PromptTemplate;
import com.zhuangjie.sentinel.db.mapper.PromptTemplateMapper;
import org.springframework.stereotype.Service;

@Service
public class PromptTemplateDbService extends ServiceImpl<PromptTemplateMapper, PromptTemplate> {
}

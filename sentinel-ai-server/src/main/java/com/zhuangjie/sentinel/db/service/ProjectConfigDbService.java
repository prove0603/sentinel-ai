package com.zhuangjie.sentinel.db.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zhuangjie.sentinel.db.entity.ProjectConfig;
import com.zhuangjie.sentinel.db.mapper.ProjectConfigMapper;
import org.springframework.stereotype.Service;

@Service
public class ProjectConfigDbService extends ServiceImpl<ProjectConfigMapper, ProjectConfig> {
}

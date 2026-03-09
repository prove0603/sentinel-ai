package com.zhuangjie.sentinel.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.zhuangjie.sentinel.db.entity.ProjectConfig;
import com.zhuangjie.sentinel.db.service.ProjectConfigDbService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ProjectService {

    private final ProjectConfigDbService projectConfigDbService;

    public List<ProjectConfig> listAll() {
        return projectConfigDbService.list(
                new LambdaQueryWrapper<ProjectConfig>()
                        .eq(ProjectConfig::getStatus, 1)
                        .orderByAsc(ProjectConfig::getId));
    }

    public ProjectConfig getById(Long id) {
        return projectConfigDbService.getById(id);
    }

    public Page<ProjectConfig> page(int current, int size) {
        return projectConfigDbService.page(
                new Page<>(current, size),
                new LambdaQueryWrapper<ProjectConfig>()
                        .orderByDesc(ProjectConfig::getUpdateTime));
    }

    public boolean save(ProjectConfig config) {
        config.setStatus(1);
        return projectConfigDbService.save(config);
    }

    public boolean update(ProjectConfig config) {
        return projectConfigDbService.updateById(config);
    }

    public boolean delete(Long id) {
        return projectConfigDbService.removeById(id);
    }
}

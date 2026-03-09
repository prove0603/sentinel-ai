package com.zhuangjie.sentinel.controller;

import com.zhuangjie.sentinel.common.PageResult;
import com.zhuangjie.sentinel.common.Result;
import com.zhuangjie.sentinel.db.entity.ProjectConfig;
import com.zhuangjie.sentinel.service.ProjectService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/project")
@RequiredArgsConstructor
public class ProjectController {

    private final ProjectService projectService;

    @GetMapping("/list")
    public Result<List<ProjectConfig>> list() {
        return Result.ok(projectService.listAll());
    }

    @GetMapping("/page")
    public Result<PageResult<ProjectConfig>> page(
            @RequestParam(defaultValue = "1") int current,
            @RequestParam(defaultValue = "10") int size) {
        return Result.ok(PageResult.of(projectService.page(current, size)));
    }

    @GetMapping("/{id}")
    public Result<ProjectConfig> get(@PathVariable Long id) {
        return Result.ok(projectService.getById(id));
    }

    @PostMapping
    public Result<Void> create(@RequestBody ProjectConfig config) {
        projectService.save(config);
        return Result.ok();
    }

    @PutMapping
    public Result<Void> update(@RequestBody ProjectConfig config) {
        projectService.update(config);
        return Result.ok();
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        projectService.delete(id);
        return Result.ok();
    }
}

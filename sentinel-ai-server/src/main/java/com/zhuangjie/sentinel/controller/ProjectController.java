package com.zhuangjie.sentinel.controller;

import com.zhuangjie.sentinel.common.PageResult;
import com.zhuangjie.sentinel.common.Result;
import com.zhuangjie.sentinel.db.entity.ProjectConfig;
import com.zhuangjie.sentinel.delta.GitRepoManager;
import com.zhuangjie.sentinel.delta.GitRepoManager.RepoStatus;
import com.zhuangjie.sentinel.service.ProjectService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/project")
@RequiredArgsConstructor
public class ProjectController {

    private final ProjectService projectService;
    private final GitRepoManager gitRepoManager;

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
        fillClonePath(config);
        projectService.save(config);
        return Result.ok();
    }

    @PutMapping
    public Result<Void> update(@RequestBody ProjectConfig config) {
        fillClonePath(config);
        projectService.update(config);
        return Result.ok();
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        projectService.delete(id);
        return Result.ok();
    }

    /**
     * Check the local repository status (exists, is git repo, file count).
     */
    @GetMapping("/check-repo/{id}")
    public Result<RepoStatus> checkRepo(@PathVariable Long id) {
        ProjectConfig config = projectService.getById(id);
        if (config == null) {
            return Result.fail("项目不存在");
        }
        String repoPath = config.getGitRepoPath();
        RepoStatus status = gitRepoManager.checkRepoStatus(repoPath);
        return Result.ok(status);
    }

    /**
     * Check repo status by path (for unsaved projects).
     */
    @GetMapping("/check-repo-path")
    public Result<RepoStatus> checkRepoByPath(@RequestParam String path) {
        return Result.ok(gitRepoManager.checkRepoStatus(path));
    }

    /**
     * Clone (or pull) a remote git repo synchronously.
     * Returns the local clone path and status.
     */
    @PostMapping("/clone/{id}")
    public Result<Map<String, Object>> cloneRepo(@PathVariable Long id) {
        ProjectConfig config = projectService.getById(id);
        if (config == null) {
            return Result.fail("项目不存在");
        }
        if (config.getGitRemoteUrl() == null || config.getGitRemoteUrl().isBlank()) {
            return Result.fail("该项目未配置 Git 远程 URL");
        }
        try {
            Path localPath = gitRepoManager.syncRepo(config);
            RepoStatus status = gitRepoManager.checkRepoStatus(localPath.toString());
            return Result.ok(Map.of(
                    "localPath", localPath.toAbsolutePath().toString(),
                    "status", status
            ));
        } catch (Exception e) {
            log.error("[Clone] Failed for project {}: {}", config.getProjectName(), e.getMessage(), e);
            return Result.fail("Clone/Pull 失败: " + e.getMessage());
        }
    }

    /**
     * Preview the clone path without actually cloning.
     */
    @GetMapping("/clone-path-preview")
    public Result<String> clonePathPreview(@RequestParam String projectName) {
        ProjectConfig temp = new ProjectConfig();
        temp.setProjectName(projectName);
        Path cloneDir = gitRepoManager.getCloneDir(temp);
        return Result.ok(cloneDir.toAbsolutePath().toString());
    }

    /**
     * For remote mode, auto-fill gitRepoPath with the computed clone directory.
     */
    private void fillClonePath(ProjectConfig config) {
        if (config.getGitRemoteUrl() != null && !config.getGitRemoteUrl().isBlank()
                && config.getProjectName() != null) {
            Path cloneDir = gitRepoManager.getCloneDir(config);
            config.setGitRepoPath(cloneDir.toAbsolutePath().toString());
        }
    }
}

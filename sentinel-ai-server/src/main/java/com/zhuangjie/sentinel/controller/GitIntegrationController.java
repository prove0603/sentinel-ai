package com.zhuangjie.sentinel.controller;

import com.zhuangjie.sentinel.common.Result;
import com.zhuangjie.sentinel.db.entity.ProjectConfig;
import com.zhuangjie.sentinel.git.GitConfig;
import com.zhuangjie.sentinel.git.GitPlatformClient;
import com.zhuangjie.sentinel.git.GitPlatformClient.*;
import com.zhuangjie.sentinel.git.GitPlatformClientFactory;
import com.zhuangjie.sentinel.service.ProjectService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/git")
@RequiredArgsConstructor
public class GitIntegrationController {

    private final ProjectService projectService;
    private final GitPlatformClientFactory clientFactory;
    private final GitConfig gitConfig;

    @GetMapping("/config")
    public Result<Map<String, Object>> getGitConfig() {
        return Result.ok(Map.of(
                "platform", gitConfig.getPlatform() != null ? gitConfig.getPlatform() : "NONE",
                "apiUrl", gitConfig.getApiUrl() != null ? gitConfig.getApiUrl() : "",
                "enabled", gitConfig.isEnabled()
        ));
    }

    @GetMapping("/branches/{projectId}")
    public Result<List<BranchInfo>> branches(@PathVariable Long projectId) {
        ProjectConfig config = getAndValidate(projectId);
        try {
            GitPlatformClient client = clientFactory.getClient(gitConfig.getPlatform());
            List<BranchInfo> branches = client.listBranches(
                    gitConfig.getApiUrl(), gitConfig.getAccessToken(), config.getGitProjectPath());
            return Result.ok(branches);
        } catch (Exception e) {
            log.error("[Git] Failed to list branches for project {}", projectId, e);
            return Result.fail("获取分支列表失败: " + e.getMessage());
        }
    }

    @GetMapping("/commits/{projectId}")
    public Result<List<CommitInfo>> commits(
            @PathVariable Long projectId,
            @RequestParam(defaultValue = "main") String branch,
            @RequestParam(defaultValue = "20") int limit) {
        ProjectConfig config = getAndValidate(projectId);
        try {
            GitPlatformClient client = clientFactory.getClient(gitConfig.getPlatform());
            List<CommitInfo> commits = client.listCommits(
                    gitConfig.getApiUrl(), gitConfig.getAccessToken(), config.getGitProjectPath(),
                    branch, limit);
            return Result.ok(commits);
        } catch (Exception e) {
            log.error("[Git] Failed to list commits for project {}", projectId, e);
            return Result.fail("获取提交历史失败: " + e.getMessage());
        }
    }

    @GetMapping("/diff/{projectId}")
    public Result<DiffInfo> diff(
            @PathVariable Long projectId,
            @RequestParam String from,
            @RequestParam String to) {
        ProjectConfig config = getAndValidate(projectId);
        try {
            GitPlatformClient client = clientFactory.getClient(gitConfig.getPlatform());
            DiffInfo diff = client.compare(
                    gitConfig.getApiUrl(), gitConfig.getAccessToken(), config.getGitProjectPath(),
                    from, to);
            return Result.ok(diff);
        } catch (Exception e) {
            log.error("[Git] Failed to compare for project {}", projectId, e);
            return Result.fail("获取变更详情失败: " + e.getMessage());
        }
    }

    @GetMapping("/test/{projectId}")
    public Result<Map<String, Object>> testConnection(@PathVariable Long projectId) {
        ProjectConfig config = getAndValidate(projectId);
        try {
            GitPlatformClient client = clientFactory.getClient(gitConfig.getPlatform());
            List<BranchInfo> branches = client.listBranches(
                    gitConfig.getApiUrl(), gitConfig.getAccessToken(), config.getGitProjectPath());
            return Result.ok(Map.of(
                    "connected", true,
                    "platform", gitConfig.getPlatform(),
                    "branchCount", branches.size()
            ));
        } catch (Exception e) {
            log.error("[Git] Connection test failed for project {}", projectId, e);
            return Result.fail("连接测试失败: " + e.getMessage());
        }
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public Result<Void> handleIllegalArg(IllegalArgumentException e) {
        return Result.fail(e.getMessage());
    }

    private ProjectConfig getAndValidate(Long projectId) {
        if (!gitConfig.isEnabled()) {
            throw new IllegalArgumentException("Git 平台未配置，请在 application.yml 中配置 sentinel.git.platform / api-url / access-token");
        }
        ProjectConfig config = projectService.getById(projectId);
        if (config == null) {
            throw new IllegalArgumentException("项目不存在");
        }
        if (config.getGitProjectPath() == null || config.getGitProjectPath().isBlank()) {
            throw new IllegalArgumentException("该项目未配置 Git 项目路径（gitProjectPath），请在项目管理中编辑");
        }
        return config;
    }
}

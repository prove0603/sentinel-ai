package com.zhuangjie.sentinel.controller;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.zhuangjie.sentinel.common.Result;
import com.zhuangjie.sentinel.db.entity.ProjectConfig;
import com.zhuangjie.sentinel.db.service.ProjectConfigDbService;
import com.zhuangjie.sentinel.service.ScanService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;

/**
 * Receives webhook push events from GitLab and GitHub,
 * then triggers incremental scans for matching projects.
 */
@Slf4j
@RestController
@RequestMapping("/api/webhook")
@RequiredArgsConstructor
public class WebhookController {

    private final ProjectConfigDbService projectConfigDbService;
    private final ScanService scanService;

    @PostMapping("/gitlab")
    public Result<String> gitlabWebhook(
            @RequestHeader(value = "X-Gitlab-Token", required = false) String gitlabToken,
            @RequestBody String body) {

        JSONObject payload = JSONUtil.parseObj(body);
        String ref = payload.getStr("ref");
        String repoUrl = payload.getByPath("project.git_ssh_url", String.class);
        String repoHttpUrl = payload.getByPath("project.git_http_url", String.class);

        if (ref == null || repoUrl == null) {
            return Result.fail("Invalid GitLab webhook payload");
        }

        String branch = ref.replace("refs/heads/", "");
        log.info("GitLab webhook received: repo={}, branch={}", repoUrl, branch);

        List<ProjectConfig> projects = findMatchingProjects(repoUrl, repoHttpUrl, branch);
        if (projects.isEmpty()) {
            log.info("No matching project found for repo={}, branch={}", repoUrl, branch);
            return Result.ok("No matching project");
        }

        for (ProjectConfig project : projects) {
            if (project.getWebhookSecret() != null && !project.getWebhookSecret().isBlank()) {
                if (!project.getWebhookSecret().equals(gitlabToken)) {
                    log.warn("Webhook secret mismatch for project: {}", project.getProjectName());
                    continue;
                }
            }
            log.info("Triggering scan for project: {} (id={})", project.getProjectName(), project.getId());
            scanService.triggerScan(project.getId(), false);
        }

        return Result.ok("Scan triggered for " + projects.size() + " project(s)");
    }

    @PostMapping("/github")
    public Result<String> githubWebhook(
            @RequestHeader(value = "X-Hub-Signature-256", required = false) String signature,
            @RequestHeader(value = "X-GitHub-Event", required = false) String event,
            @RequestBody String body) {

        if (!"push".equals(event)) {
            return Result.ok("Ignored event: " + event);
        }

        JSONObject payload = JSONUtil.parseObj(body);
        String ref = payload.getStr("ref");
        String repoUrl = payload.getByPath("repository.ssh_url", String.class);
        String repoCloneUrl = payload.getByPath("repository.clone_url", String.class);

        if (ref == null || repoUrl == null) {
            return Result.fail("Invalid GitHub webhook payload");
        }

        String branch = ref.replace("refs/heads/", "");
        log.info("GitHub webhook received: repo={}, branch={}", repoUrl, branch);

        List<ProjectConfig> projects = findMatchingProjects(repoUrl, repoCloneUrl, branch);
        if (projects.isEmpty()) {
            log.info("No matching project found for repo={}, branch={}", repoUrl, branch);
            return Result.ok("No matching project");
        }

        for (ProjectConfig project : projects) {
            if (project.getWebhookSecret() != null && !project.getWebhookSecret().isBlank()
                    && signature != null) {
                if (!verifyGitHubSignature(body, project.getWebhookSecret(), signature)) {
                    log.warn("GitHub signature verification failed for project: {}", project.getProjectName());
                    continue;
                }
            }
            log.info("Triggering scan for project: {} (id={})", project.getProjectName(), project.getId());
            scanService.triggerScan(project.getId(), false);
        }

        return Result.ok("Scan triggered for " + projects.size() + " project(s)");
    }

    private List<ProjectConfig> findMatchingProjects(String sshUrl, String httpUrl, String branch) {
        List<ProjectConfig> allActive = projectConfigDbService.list(
                new LambdaQueryWrapper<ProjectConfig>()
                        .eq(ProjectConfig::getStatus, 1)
                        .isNotNull(ProjectConfig::getGitRemoteUrl));

        return allActive.stream()
                .filter(p -> {
                    String remoteUrl = p.getGitRemoteUrl();
                    boolean urlMatch = remoteUrl.equalsIgnoreCase(sshUrl)
                            || remoteUrl.equalsIgnoreCase(httpUrl)
                            || normalizeGitUrl(remoteUrl).equals(normalizeGitUrl(sshUrl))
                            || (httpUrl != null && normalizeGitUrl(remoteUrl).equals(normalizeGitUrl(httpUrl)));
                    String projectBranch = p.getGitBranch() != null ? p.getGitBranch() : "master";
                    return urlMatch && projectBranch.equals(branch);
                })
                .toList();
    }

    private String normalizeGitUrl(String url) {
        if (url == null) return "";
        return url.toLowerCase()
                .replaceAll("^(https?://|git@)", "")
                .replace(":", "/")
                .replaceAll("\\.git$", "")
                .replaceAll("/$", "");
    }

    private boolean verifyGitHubSignature(String payload, String secret, String signature) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hash = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            String expected = "sha256=" + HexFormat.of().formatHex(hash);
            return expected.equals(signature);
        } catch (Exception e) {
            log.error("Failed to verify GitHub signature: {}", e.getMessage());
            return false;
        }
    }
}

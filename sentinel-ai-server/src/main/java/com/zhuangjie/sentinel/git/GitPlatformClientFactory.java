package com.zhuangjie.sentinel.git;

import org.springframework.stereotype.Component;

@Component
public class GitPlatformClientFactory {

    private final GitLabClient gitLabClient = new GitLabClient();
    private final GitHubClient gitHubClient = new GitHubClient();

    public GitPlatformClient getClient(String platform) {
        return switch (platform.toUpperCase()) {
            case "GITLAB" -> gitLabClient;
            case "GITHUB" -> gitHubClient;
            default -> throw new IllegalArgumentException("Unsupported git platform: " + platform);
        };
    }
}

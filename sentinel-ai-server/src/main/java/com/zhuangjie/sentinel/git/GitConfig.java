package com.zhuangjie.sentinel.git;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "sentinel.git")
public class GitConfig {

    /** NONE / GITLAB / GITHUB */
    private String platform = "NONE";

    /** Git platform API base URL, e.g. https://gitlab.company.com or https://api.github.com */
    private String apiUrl;

    /** Personal access token (GitLab: PRIVATE-TOKEN, GitHub: Bearer token) */
    private String accessToken;

    /** Base directory for cloned repos */
    private String cloneBaseDir;

    public boolean isEnabled() {
        return platform != null && !"NONE".equalsIgnoreCase(platform)
                && accessToken != null && !accessToken.isBlank();
    }
}

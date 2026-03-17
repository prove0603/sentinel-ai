package com.zhuangjie.sentinel.delta;

import com.zhuangjie.sentinel.db.entity.ProjectConfig;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.PullResult;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Manages local git repository clones for remote projects.
 * <p>
 * For projects with gitRemoteUrl configured, maintains a local clone
 * under the clone-base-dir. Supports clone and pull operations.
 */
@Slf4j
@Component
public class GitRepoManager {

    @Value("${sentinel.git.clone-base-dir:${user.home}/.sentinel-ai/repos}")
    private String cloneBaseDir;

    /**
     * Resolves the local path to the project's git repository.
     * - If gitRemoteUrl is set: uses managed clone directory
     * - Otherwise: uses the legacy gitRepoPath field
     */
    public Path resolveRepoPath(ProjectConfig project) {
        if (project.getGitRemoteUrl() != null && !project.getGitRemoteUrl().isBlank()) {
            return getCloneDir(project);
        }
        return Path.of(project.getGitRepoPath());
    }

    /**
     * Ensures the local clone is up-to-date. Clones if not present, pulls if exists.
     *
     * @return the local repo path after sync
     */
    public Path syncRepo(ProjectConfig project) throws IOException, GitAPIException {
        if (project.getGitRemoteUrl() == null || project.getGitRemoteUrl().isBlank()) {
            Path localPath = Path.of(project.getGitRepoPath());
            log.debug("Project {} uses local repo: {}", project.getProjectName(), localPath);
            return localPath;
        }

        Path cloneDir = getCloneDir(project);
        String branch = project.getGitBranch() != null ? project.getGitBranch() : "master";

        if (Files.exists(cloneDir.resolve(".git"))) {
            return pullRepo(cloneDir, branch, project);
        } else {
            return cloneRepo(project.getGitRemoteUrl(), cloneDir, branch, project);
        }
    }

    private Path cloneRepo(String remoteUrl, Path targetDir, String branch, ProjectConfig project)
            throws IOException, GitAPIException {
        Files.createDirectories(targetDir.getParent());
        log.info("Cloning {} (branch: {}) into {}", remoteUrl, branch, targetDir);

        Git.cloneRepository()
                .setURI(remoteUrl)
                .setDirectory(targetDir.toFile())
                .setBranch(branch)
                .setCloneAllBranches(false)
                .call()
                .close();

        log.info("Clone completed for project: {}", project.getProjectName());
        return targetDir;
    }

    private Path pullRepo(Path repoDir, String branch, ProjectConfig project)
            throws IOException, GitAPIException {
        log.info("Pulling latest for project: {} (branch: {})", project.getProjectName(), branch);

        try (Git git = Git.open(repoDir.toFile())) {
            git.checkout().setName(branch).call();
            PullResult result = git.pull().setRemoteBranchName(branch).call();

            if (result.isSuccessful()) {
                log.info("Pull successful for project: {}", project.getProjectName());
            } else {
                log.warn("Pull had issues for project: {}, merge: {}",
                        project.getProjectName(), result.getMergeResult());
            }
        }
        return repoDir;
    }

    private Path getCloneDir(ProjectConfig project) {
        String safeName = project.getProjectName().replaceAll("[^a-zA-Z0-9_-]", "_");
        return Path.of(cloneBaseDir, safeName);
    }
}

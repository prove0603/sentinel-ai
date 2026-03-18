package com.zhuangjie.sentinel.delta;

import com.zhuangjie.sentinel.db.entity.ProjectConfig;
import com.zhuangjie.sentinel.git.GitConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.PullResult;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Manages local git repository clones for remote projects.
 * <p>
 * For projects with gitRemoteUrl configured, maintains a local clone
 * under the clone-base-dir. Supports clone and pull operations.
 * Uses the global GitConfig access-token for HTTPS authentication.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GitRepoManager {

    private final GitConfig gitConfig;

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

        var cmd = Git.cloneRepository()
                .setURI(remoteUrl)
                .setDirectory(targetDir.toFile())
                .setBranch(branch)
                .setCloneAllBranches(false);

        CredentialsProvider cred = getCredentialsProvider();
        if (cred != null) {
            cmd.setCredentialsProvider(cred);
        }

        cmd.call().close();

        log.info("Clone completed for project: {}", project.getProjectName());
        return targetDir;
    }

    private Path pullRepo(Path repoDir, String branch, ProjectConfig project)
            throws IOException, GitAPIException {
        log.info("Pulling latest for project: {} (branch: {})", project.getProjectName(), branch);

        try (Git git = Git.open(repoDir.toFile())) {
            git.checkout().setName(branch).call();

            var pullCmd = git.pull().setRemoteBranchName(branch);
            CredentialsProvider cred = getCredentialsProvider();
            if (cred != null) {
                pullCmd.setCredentialsProvider(cred);
            }

            PullResult result = pullCmd.call();

            if (result.isSuccessful()) {
                log.info("Pull successful for project: {}", project.getProjectName());
            } else {
                log.warn("Pull had issues for project: {}, merge: {}",
                        project.getProjectName(), result.getMergeResult());
            }
        }
        return repoDir;
    }

    private CredentialsProvider getCredentialsProvider() {
        String token = gitConfig.getAccessToken();
        if (token != null && !token.isBlank()) {
            return new UsernamePasswordCredentialsProvider("oauth2", token);
        }
        return null;
    }

    /**
     * Returns the computed clone directory for a remote project.
     */
    public Path getCloneDir(ProjectConfig project) {
        String dirName = extractRepoName(project.getGitRemoteUrl());
        if (dirName == null || dirName.isBlank()) {
            dirName = project.getProjectName();
        }
        dirName = dirName.replaceAll("[^a-zA-Z0-9_-]", "_");
        String baseDir = gitConfig.getCloneBaseDir();
        if (baseDir == null || baseDir.isBlank()) {
            baseDir = System.getProperty("user.home") + "/.sentinel-ai/repos";
        }
        return Path.of(baseDir, dirName);
    }

    /**
     * Extracts the repository name from a git remote URL.
     * e.g. "https://git.silvrr.com/risk-backend/collection-management.git" → "collection-management"
     */
    private String extractRepoName(String remoteUrl) {
        if (remoteUrl == null || remoteUrl.isBlank()) return null;
        String url = remoteUrl.trim();
        if (url.endsWith(".git")) url = url.substring(0, url.length() - 4);
        if (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        int lastSlash = url.lastIndexOf('/');
        return lastSlash >= 0 ? url.substring(lastSlash + 1) : url;
    }

    /**
     * Checks the local repo status for a given path.
     *
     * @return a record describing whether the path exists, has a .git dir, and file count
     */
    public RepoStatus checkRepoStatus(String repoPath) {
        if (repoPath == null || repoPath.isBlank()) {
            return new RepoStatus(false, false, 0, repoPath);
        }
        Path path = Path.of(repoPath);
        boolean exists = Files.exists(path);
        boolean isGitRepo = Files.exists(path.resolve(".git"));
        long fileCount = 0;
        if (exists) {
            try (var stream = Files.list(path)) {
                fileCount = stream.count();
            } catch (IOException e) {
                log.warn("Failed to list files in {}: {}", path, e.getMessage());
            }
        }
        return new RepoStatus(exists, isGitRepo, fileCount, path.toAbsolutePath().toString());
    }

    public record RepoStatus(boolean exists, boolean isGitRepo, long fileCount, String absolutePath) {}
}

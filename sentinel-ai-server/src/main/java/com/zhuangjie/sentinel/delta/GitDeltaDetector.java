package com.zhuangjie.sentinel.delta;

import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.LogCommand;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.util.io.DisabledOutputStream;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Uses JGit to detect file changes between two Git commits.
 */
@Slf4j
@Component
public class GitDeltaDetector {

    /**
     * Resolves the current HEAD commit hash for the given repository.
     *
     * @return HEAD commit hash, or null if the repo is invalid
     */
    public String resolveHead(Path repoRoot) {
        try (Repository repo = openRepository(repoRoot)) {
            ObjectId head = repo.resolve("HEAD");
            return head != null ? head.getName() : null;
        } catch (IOException e) {
            log.warn("Failed to resolve HEAD for repo: {}", repoRoot, e);
            return null;
        }
    }

    /**
     * Detects changed files between two commits.
     *
     * @param repoRoot       path to the git repository root
     * @param fromCommitHash the base commit hash
     * @param toCommitHash   the target commit hash (null = HEAD)
     * @return delta result with added, modified, and deleted file lists
     */
    public DeltaResult detectChanges(Path repoRoot, String fromCommitHash, String toCommitHash) {
        try (Repository repo = openRepository(repoRoot);
             RevWalk revWalk = new RevWalk(repo)) {

            ObjectId fromId = repo.resolve(fromCommitHash);
            if (fromId == null) {
                throw new IllegalArgumentException("Cannot resolve commit: " + fromCommitHash);
            }

            ObjectId toId = toCommitHash != null
                    ? repo.resolve(toCommitHash)
                    : repo.resolve("HEAD");
            if (toId == null) {
                throw new IllegalArgumentException("Cannot resolve target commit");
            }

            RevCommit fromCommit = revWalk.parseCommit(fromId);
            RevCommit toCommit = revWalk.parseCommit(toId);

            try (DiffFormatter formatter = new DiffFormatter(DisabledOutputStream.INSTANCE)) {
                formatter.setRepository(repo);
                formatter.setDetectRenames(true);

                List<DiffEntry> diffs = formatter.scan(fromCommit.getTree(), toCommit.getTree());

                List<String> added = new ArrayList<>();
                List<String> modified = new ArrayList<>();
                List<String> deleted = new ArrayList<>();

                for (DiffEntry diff : diffs) {
                    switch (diff.getChangeType()) {
                        case ADD -> added.add(diff.getNewPath());
                        case MODIFY -> modified.add(diff.getNewPath());
                        case DELETE -> deleted.add(diff.getOldPath());
                        case RENAME -> {
                            deleted.add(diff.getOldPath());
                            added.add(diff.getNewPath());
                        }
                        case COPY -> added.add(diff.getNewPath());
                    }
                }

                String resolvedTo = toId.getName();
                log.info("Git delta: {}..{} — added={}, modified={}, deleted={}, total={}",
                        abbrev(fromCommitHash), abbrev(resolvedTo),
                        added.size(), modified.size(), deleted.size(),
                        added.size() + modified.size() + deleted.size());

                return new DeltaResult(fromCommitHash, resolvedTo, added, modified, deleted);
            }
        } catch (IOException e) {
            log.error("Failed to detect git changes: {} → {}", fromCommitHash, toCommitHash, e);
            throw new RuntimeException("Failed to detect git changes", e);
        }
    }

    /**
     * 获取 HEAD commit 的作者信息（用户名）。
     */
    public String resolveHeadAuthor(Path repoRoot) {
        try (Repository repo = openRepository(repoRoot);
             RevWalk revWalk = new RevWalk(repo)) {
            ObjectId head = repo.resolve("HEAD");
            if (head == null) return null;
            RevCommit commit = revWalk.parseCommit(head);
            PersonIdent author = commit.getAuthorIdent();
            return author != null ? author.getName() : null;
        } catch (IOException e) {
            log.warn("Failed to resolve HEAD author for repo: {}", repoRoot, e);
            return null;
        }
    }

    /**
     * 获取 HEAD commit 的作者邮箱。
     */
    public String resolveHeadAuthorEmail(Path repoRoot) {
        try (Repository repo = openRepository(repoRoot);
             RevWalk revWalk = new RevWalk(repo)) {
            ObjectId head = repo.resolve("HEAD");
            if (head == null) return null;
            RevCommit commit = revWalk.parseCommit(head);
            PersonIdent author = commit.getAuthorIdent();
            return author != null ? author.getEmailAddress() : null;
        } catch (IOException e) {
            log.warn("Failed to resolve HEAD author email for repo: {}", repoRoot, e);
            return null;
        }
    }

    /**
     * 获取指定文件最新一次提交的作者名。
     * 等价于 git log -1 --format='%an' -- filePath
     */
    public String resolveFileAuthor(Path repoRoot, String filePath) {
        try (Repository repo = openRepository(repoRoot);
             Git git = new Git(repo)) {
            LogCommand logCmd = git.log().addPath(filePath).setMaxCount(1);
            for (RevCommit commit : logCmd.call()) {
                PersonIdent author = commit.getAuthorIdent();
                return author != null ? author.getName() : null;
            }
            return null;
        } catch (Exception e) {
            log.debug("Failed to resolve author for file {}: {}", filePath, e.getMessage());
            return null;
        }
    }

    /**
     * 批量获取多个文件的最新提交者。
     * 对相同文件只查询一次，返回 filePath → authorName 映射。
     */
    public Map<String, String> resolveFileAuthors(Path repoRoot, List<String> filePaths) {
        Map<String, String> result = new HashMap<>();
        if (filePaths == null || filePaths.isEmpty()) return result;

        try (Repository repo = openRepository(repoRoot);
             Git git = new Git(repo)) {
            for (String filePath : filePaths) {
                if (result.containsKey(filePath)) continue;
                try {
                    LogCommand logCmd = git.log().addPath(filePath).setMaxCount(1);
                    for (RevCommit commit : logCmd.call()) {
                        PersonIdent author = commit.getAuthorIdent();
                        if (author != null) {
                            result.put(filePath, author.getName());
                        }
                        break;
                    }
                } catch (Exception e) {
                    log.debug("Failed to resolve author for file {}: {}", filePath, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("Failed to open repository for batch author resolution: {}", e.getMessage());
        }
        return result;
    }

    private Repository openRepository(Path repoRoot) throws IOException {
        return new FileRepositoryBuilder()
                .setGitDir(repoRoot.resolve(".git").toFile())
                .readEnvironment()
                .build();
    }

    private static String abbrev(String hash) {
        return hash != null && hash.length() > 8 ? hash.substring(0, 8) : hash;
    }
}

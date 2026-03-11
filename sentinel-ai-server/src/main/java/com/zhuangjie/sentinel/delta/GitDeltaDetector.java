package com.zhuangjie.sentinel.delta;

import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.util.io.DisabledOutputStream;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

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

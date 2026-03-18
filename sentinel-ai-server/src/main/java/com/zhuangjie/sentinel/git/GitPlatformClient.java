package com.zhuangjie.sentinel.git;

import java.io.IOException;
import java.util.List;

/**
 * Abstraction for Git platform REST APIs (GitLab / GitHub).
 * Each method takes explicit connection params so the client itself is stateless.
 */
public interface GitPlatformClient {

    List<BranchInfo> listBranches(String apiUrl, String token, String projectPath) throws IOException, InterruptedException;

    List<CommitInfo> listCommits(String apiUrl, String token, String projectPath, String branch, int limit) throws IOException, InterruptedException;

    DiffInfo compare(String apiUrl, String token, String projectPath, String fromCommit, String toCommit) throws IOException, InterruptedException;

    record BranchInfo(String name, String lastCommitId, String lastCommitMessage, String lastCommitTime) {}

    record CommitInfo(String id, String shortId, String title, String author, String createdAt, List<String> parentIds) {}

    record DiffInfo(String fromCommit, String toCommit, List<DiffFile> files) {}

    record DiffFile(String path, String status, int additions, int deletions) {}
}

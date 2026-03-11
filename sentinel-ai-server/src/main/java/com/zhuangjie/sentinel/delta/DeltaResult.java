package com.zhuangjie.sentinel.delta;

import java.util.List;
import java.util.stream.Stream;

/**
 * Holds the result of a Git diff between two commits.
 *
 * @param fromCommit     source commit hash
 * @param toCommit       target commit hash
 * @param addedFiles     files added (relative to repo root)
 * @param modifiedFiles  files modified
 * @param deletedFiles   files deleted
 */
public record DeltaResult(
        String fromCommit,
        String toCommit,
        List<String> addedFiles,
        List<String> modifiedFiles,
        List<String> deletedFiles
) {

    public List<String> changedMapperXmlFiles() {
        return Stream.concat(addedFiles.stream(), modifiedFiles.stream())
                .filter(f -> f.endsWith(".xml"))
                .filter(f -> !f.contains("/test/"))
                .toList();
    }

    public List<String> deletedMapperXmlFiles() {
        return deletedFiles.stream()
                .filter(f -> f.endsWith(".xml"))
                .toList();
    }

    public List<String> changedJavaFiles() {
        return Stream.concat(addedFiles.stream(), modifiedFiles.stream())
                .filter(f -> f.endsWith(".java"))
                .filter(f -> !f.contains("/test/"))
                .toList();
    }

    public List<String> deletedJavaFiles() {
        return deletedFiles.stream()
                .filter(f -> f.endsWith(".java"))
                .toList();
    }

    public boolean hasChanges() {
        return !addedFiles.isEmpty() || !modifiedFiles.isEmpty() || !deletedFiles.isEmpty();
    }

    public int totalChangedFileCount() {
        return addedFiles.size() + modifiedFiles.size() + deletedFiles.size();
    }
}

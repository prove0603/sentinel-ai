package com.zhuangjie.sentinel.scanner;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Shared utility for recursively walking a project directory to find Java source files.
 * Skips common non-source directories (.git, node_modules, target, build, test).
 */
@Slf4j
public final class JavaFileWalker {

    private static final Set<String> SKIP_DIRS = Set.of(
            ".git", "node_modules", "target", "build", ".idea", ".mvn"
    );

    private JavaFileWalker() {
    }

    public static List<Path> findJavaFiles(Path projectRoot) {
        List<Path> javaFiles = new ArrayList<>();
        try {
            Files.walkFileTree(projectRoot, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    String fileName = file.getFileName().toString();
                    if (fileName.endsWith(".java") && !isTestPath(file)) {
                        javaFiles.add(file);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    String dirName = dir.getFileName().toString();
                    if (SKIP_DIRS.contains(dirName)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    log.debug("Cannot access file: {}", file);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            log.error("Failed to walk project directory: {}", projectRoot, e);
        }
        return javaFiles;
    }

    private static boolean isTestPath(Path file) {
        String path = file.toString().replace('\\', '/').toLowerCase();
        return path.contains("/test/") || path.contains("/tests/");
    }
}

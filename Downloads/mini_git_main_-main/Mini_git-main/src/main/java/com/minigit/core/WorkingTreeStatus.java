package com.minigit.core;

import com.minigit.util.HashUtil;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

/** Calculates working-tree changes against HEAD and the staging index. */
public class WorkingTreeStatus {

    public record FileStatus(
            String path,
            String status,
            String changeType,
            String renameFrom,
            boolean staged,
            boolean workingChanged
    ) {
    }

    private final Repository repository;

    public WorkingTreeStatus(Repository repository) {
        this.repository = repository;
    }

    public List<FileStatus> getFiles() throws IOException {
        if (!repository.isInitialized()) {
            throw new IllegalStateException(
                    "MiniGit repository is not initialized. "
                            + "Run 'init' first."
            );
        }

        Path metadata = repository.getMetadataDirectory();
        Path workingDirectory = repository.getWorkingDirectory();
        Map<String, String> staged = new Index(metadata).getEntries();
        String head = new BranchManager(repository).getCurrentCommit();
        Map<String, String> committed = new CommitStore(metadata)
                .readSnapshot(head);
        Map<String, String> workingFiles = new TreeMap<>();
        Set<String> discovered = new HashSet<>();

        try (Stream<Path> paths = Files.walk(workingDirectory)) {
            paths.filter(Files::isRegularFile)
                    .filter(path -> !path.startsWith(metadata))
                    .filter(path -> !isIgnored(workingDirectory.relativize(path)))
                    .forEach(path -> {
                        String relative = workingDirectory.relativize(path)
                                .toString()
                                .replace('\\', '/');
                        discovered.add(relative);
                    });
        }

        for (String path : discovered) {
            Path file = workingDirectory.resolve(path).normalize();
            if (file.startsWith(workingDirectory)) {
                workingFiles.put(
                        path,
                        HashUtil.sha256(Files.readAllBytes(file))
                );
            }
        }

        Set<String> allPaths = new HashSet<>(discovered);
        allPaths.addAll(staged.keySet());
        allPaths.addAll(committed.keySet());

        Map<String, FileStatus> statuses = new TreeMap<>();
        Map<String, String> deleted = new TreeMap<>();
        Map<String, String> added = new TreeMap<>();

        for (String path : allPaths) {
            String committedHash = committed.get(path);
            String stagedHash = staged.get(path);
            String workingHash = workingFiles.get(path);
            boolean exists = discovered.contains(path);
            boolean stagedDeletion = Index.isDeletion(stagedHash);

            if (!exists && committedHash != null) {
                deleted.put(path, committedHash);
            }

            if (!exists) {
                if (stagedDeletion) {
                    statuses.put(path, new FileStatus(
                            path, "STAGED", "DELETED", null, true, false
                    ));
                } else if (committedHash != null) {
                    statuses.put(path, new FileStatus(
                            path,
                            "DELETED",
                            "DELETED",
                            null,
                            false,
                            false
                    ));
                } else if (stagedHash != null) {
                    statuses.put(path, new FileStatus(
                            path,
                            "DELETED",
                            "DELETED",
                            null,
                            true,
                            true
                    ));
                }
                continue;
            }

            if (stagedDeletion) {
                statuses.put(path, new FileStatus(
                        path, "STAGED", "DELETED", null, true, true
                ));
                continue;
            }

            if (stagedHash != null) {
                boolean matchesStaged = stagedHash.equals(workingHash);
                boolean matchesHead = stagedHash.equals(committedHash);

                if (matchesStaged && !matchesHead) {
                    statuses.put(path, new FileStatus(
                            path,
                            "STAGED",
                            committedHash == null ? "ADDED" : "MODIFIED",
                            null,
                            true,
                            false
                    ));
                } else if (!matchesStaged) {
                    statuses.put(path, new FileStatus(
                            path,
                            "MODIFIED",
                            committedHash == null ? "ADDED" : "MODIFIED",
                            null,
                            false,
                            true
                    ));
                }
            } else if (committedHash != null) {
                if (!committedHash.equals(workingHash)) {
                    statuses.put(path, new FileStatus(
                            path,
                            "MODIFIED",
                            "MODIFIED",
                            null,
                            false,
                            true
                    ));
                }
            } else {
                statuses.put(path, new FileStatus(
                        path, "UNTRACKED", "ADDED", null, false, true
                ));
            }

            if (committedHash == null && workingHash != null) {
                added.put(path, workingHash);
            }
        }

        Map<String, String> renames = RenameDetector.detect(deleted, added);
        for (Map.Entry<String, String> rename : renames.entrySet()) {
            FileStatus destination = statuses.get(rename.getKey());
            if (destination == null) {
                continue;
            }
            statuses.put(rename.getKey(), new FileStatus(
                    destination.path(),
                    destination.staged() ? "STAGED" : "RENAMED",
                    "RENAMED",
                    rename.getValue(),
                    destination.staged(),
                    destination.workingChanged()
            ));
        }

        return new ArrayList<>(statuses.values());
    }

    private static boolean isIgnored(Path relativePath) {
        for (Path part : relativePath) {
            String name = part.toString();
            if ("target".equals(name)
                    || ".git".equals(name)
                    || "node_modules".equals(name)) {
                return true;
            }
        }
        return false;
    }
}

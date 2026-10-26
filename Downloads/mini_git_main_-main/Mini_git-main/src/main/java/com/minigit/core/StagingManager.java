package com.minigit.core;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;

/**
 * Module 10 (part 1) - Staging management.
 *
 * Lets the user take files back out of the staging area. Unstaging only
 * changes the index: the file in the working directory is never touched,
 * so no work can be lost.
 */
public class StagingManager {

    private final Repository repository;

    public StagingManager(Repository repository) {
        this.repository = repository;
    }

    private void requireInitialized() {
        if (!repository.isInitialized()) {
            throw new IllegalStateException(
                    "MiniGit repository is not initialized. "
                            + "Run 'init' first."
            );
        }
    }

    /** Returns the currently staged files (path to blob hash), sorted. */
    public Map<String, String> getStagedFiles() throws IOException {
        requireInitialized();

        return new TreeMap<>(
                new Index(repository.getMetadataDirectory()).getEntries()
        );
    }

    /**
     * Removes one file from the staging area.
     *
     * @return the normalised path that was unstaged
     * @throws IllegalArgumentException if the path is invalid or not staged
     */
    public String unstage(String path) throws IOException {

        requireInitialized();

        String relativePath = normalize(path);

        synchronized (RepositoryLock.LOCK) {

            Index index = new Index(repository.getMetadataDirectory());

            if (!index.remove(relativePath)) {
                throw new IllegalArgumentException(
                        "File is not staged: " + relativePath
                );
            }
        }

        return relativePath;
    }

    /**
     * Empties the staging area.
     *
     * @return how many files were unstaged
     */
    public int unstageAll() throws IOException {

        requireInitialized();

        synchronized (RepositoryLock.LOCK) {

            Index index = new Index(repository.getMetadataDirectory());
            int count = index.getEntries().size();

            if (count == 0) {
                throw new IllegalStateException(
                        "Nothing is staged."
                );
            }

            index.clear();

            return count;
        }
    }

    /**
     * Converts user input into the repository-relative path format used by
     * the index (forward slashes, no leading "./", never outside the
     * repository).
     */
    static String normalize(String path) {

        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException(
                    "Please specify a file to unstage."
            );
        }

        if (path.contains("\\")) {
            throw new IllegalArgumentException(
                    "Use forward slashes in file paths."
            );
        }

        Path parsed;

        try {
            parsed = Path.of(path.trim()).normalize();
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(
                    "Invalid file path: " + path, e
            );
        }

        String normalized = parsed.toString().replace('\\', '/');

        if (parsed.isAbsolute()
                || normalized.isEmpty()
                || normalized.equals("..")
                || normalized.startsWith("../")) {
            throw new IllegalArgumentException(
                    "Path must be inside the repository: " + path
            );
        }

        return normalized;
    }
}

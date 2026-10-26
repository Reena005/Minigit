package com.minigit.core;

import com.minigit.storage.FileObjectStore;
import com.minigit.storage.ObjectStore;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Reads MiniGit commit objects and reconstructs file snapshots.
 *
 * Older MiniGit commits stored only the files that were staged, while newer
 * commits store a complete snapshot. Walking from the newest commit to the
 * oldest and keeping the first occurrence of each path supports both.
 */
public class CommitStore {

    private static final String LEGACY_COMMIT_HEADER = "MINIGIT_COMMIT_V1";
    private static final String SNAPSHOT_COMMIT_HEADER = "MINIGIT_COMMIT_V2";
    private static final String HASH_PATTERN = "[a-fA-F0-9]{64}";

    /** A parsed commit object. */
    public record CommitData(
            String hash,
            String parent,
            String timestamp,
            String message,
            Map<String, String> files,
            boolean completeSnapshot
    ) {
        public CommitData(
                String hash,
                String parent,
                String timestamp,
                String message,
                Map<String, String> files
        ) {
            this(hash, parent, timestamp, message, files, false);
        }
    }

    private final ObjectStore objectStore;

    public CommitStore(Path repositoryMetadataDirectory) {
        this.objectStore = new FileObjectStore(repositoryMetadataDirectory);
    }

    public static boolean isValidHash(String hash) {
        return hash != null && hash.matches(HASH_PATTERN);
    }

    /** Returns true when the object exists and parses as a commit. */
    public boolean isCommit(String hash) {
        if (!isValidHash(hash)) {
            return false;
        }
        try {
            readCommit(hash);
            return true;
        } catch (IOException | IllegalArgumentException e) {
            return false;
        }
    }

    public CommitData readCommit(String commitHash) throws IOException {

        if (!isValidHash(commitHash)) {
            throw new IOException("Invalid commit hash: " + commitHash);
        }

        String hash = commitHash.toLowerCase();

        String text = new String(
                objectStore.read(hash),
                StandardCharsets.UTF_8
        );

        String[] lines = text.split("\n", -1);

        boolean completeSnapshot =
                SNAPSHOT_COMMIT_HEADER.equals(lines[0]);
        if (!completeSnapshot && !LEGACY_COMMIT_HEADER.equals(lines[0])) {
            throw new IOException("Invalid commit object: " + hash);
        }

        String parent = null;
        String timestamp = null;
        String message = null;
        int fileCount = -1;
        int index = 1;

        while (index < lines.length) {

            String line = lines[index++];

            if (line.startsWith("parent=")) {
                if (parent != null) {
                    throw new IOException("Duplicate commit parent metadata.");
                }
                parent = line.substring("parent=".length());

            } else if (line.startsWith("timestamp=")) {
                if (timestamp != null) {
                    throw new IOException("Duplicate commit timestamp metadata.");
                }
                timestamp = line.substring("timestamp=".length());

            } else if (line.startsWith("message=")) {
                if (message != null) {
                    throw new IOException("Duplicate commit message metadata.");
                }
                try {
                    message = new String(
                            Base64.getDecoder().decode(
                                    line.substring("message=".length())
                            ),
                            StandardCharsets.UTF_8
                    );
                } catch (IllegalArgumentException e) {
                    throw new IOException(
                            "Invalid commit message encoding.", e
                    );
                }

            } else if (line.startsWith("files=")) {
                try {
                    fileCount = Integer.parseInt(
                            line.substring("files=".length())
                    );
                } catch (NumberFormatException e) {
                    throw new IOException("Invalid commit file count.", e);
                }
                break;
            } else {
                throw new IOException("Invalid commit metadata: " + hash);
            }
        }

        if (parent == null || timestamp == null || message == null
                || fileCount < 0) {
            throw new IOException("Incomplete commit metadata: " + hash);
        }

        try {
            Instant.parse(timestamp);
        } catch (RuntimeException e) {
            throw new IOException("Invalid commit timestamp: " + hash, e);
        }

        if (!parent.isEmpty() && !isValidHash(parent)) {
            throw new IOException("Invalid parent commit hash: " + parent);
        }

        Map<String, String> files = new LinkedHashMap<>();

        for (int n = 0; n < fileCount; n++) {

            if (index >= lines.length) {
                throw new IOException("Missing commit file entry: " + hash);
            }

            String entry = lines[index++];
            int separator = entry.lastIndexOf('\t');

            if (separator <= 0) {
                throw new IOException("Invalid commit file entry: " + hash);
            }

            String path;

            try {
                path = new String(
                        Base64.getDecoder().decode(
                                entry.substring(0, separator)
                        ),
                        StandardCharsets.UTF_8
                );
            } catch (IllegalArgumentException e) {
                throw new IOException("Invalid encoded file path.", e);
            }

            String blobHash = entry.substring(separator + 1).toLowerCase();

            if (!isSafeRelativePath(path) || !isValidHash(blobHash)) {
                throw new IOException("Invalid file entry in commit: " + path);
            }

            if (files.put(path, blobHash) != null) {
                throw new IOException("Duplicate file entry in commit: " + path);
            }
        }

        return new CommitData(
                hash,
                parent.toLowerCase(),
                timestamp,
                message,
                files,
                completeSnapshot
        );
    }

    /**
     * Reconstructs the complete file snapshot visible at a commit.
     * An empty or null hash yields an empty snapshot.
     */
    public Map<String, String> readSnapshot(String commitHash)
            throws IOException {

        Map<String, String> snapshot = new TreeMap<>();

        if (commitHash == null || commitHash.isEmpty()) {
            return snapshot;
        }

        Set<String> visited = new HashSet<>();
        String current = commitHash;

        while (current != null && !current.isEmpty()) {

            if (!visited.add(current.toLowerCase())) {
                throw new IOException("Circular commit history detected.");
            }

            CommitData commit = readCommit(current);

            for (Map.Entry<String, String> entry
                    : commit.files().entrySet()) {
                snapshot.putIfAbsent(entry.getKey(), entry.getValue());
            }

            if (commit.completeSnapshot()) {
                break;
            }

            current = commit.parent();
        }

        return snapshot;
    }

    /**
     * Returns true when {@code ancestor} is reachable from
     * {@code descendant} by following parent links (a commit counts as its
     * own ancestor).
     */
    public boolean isAncestor(String ancestor, String descendant)
            throws IOException {

        if (ancestor == null || ancestor.isEmpty()
                || descendant == null || descendant.isEmpty()) {
            return false;
        }

        String target = ancestor.toLowerCase();
        Set<String> visited = new HashSet<>();
        String current = descendant.toLowerCase();

        while (!current.isEmpty()) {

            if (current.equals(target)) {
                return true;
            }

            if (!visited.add(current)) {
                throw new IOException("Circular commit history detected.");
            }

            current = readCommit(current).parent();
        }

        return false;
    }

    private static boolean isSafeRelativePath(String path) {

        if (path == null || path.isBlank()
                || path.startsWith("/")
                || path.contains("\\")) {
            return false;
        }

        try {
            Path parsed = Path.of(path);
            return !parsed.isAbsolute()
                    && !parsed.normalize().startsWith("..");
        } catch (RuntimeException e) {
            return false;
        }
    }
}

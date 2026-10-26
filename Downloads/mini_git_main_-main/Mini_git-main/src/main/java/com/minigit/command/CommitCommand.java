package com.minigit.command;

import com.minigit.core.CommitStore;
import com.minigit.core.Index;
import com.minigit.core.Reflog;
import com.minigit.core.Repository;
import com.minigit.storage.FileObjectStore;
import com.minigit.util.HashUtil;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.TreeMap;

public class CommitCommand {

    private final Repository repository;

    public CommitCommand(Repository repository) {
        this.repository = repository;
    }

    public void execute(String message) throws IOException {

        if (!repository.isInitialized()) {
            throw new IllegalStateException(
                    "MiniGit repository is not initialized. Run 'init' first."
            );
        }

        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException(
                    "Commit message cannot be empty."
            );
        }

        Path metadataDirectory = repository.getMetadataDirectory();
        Path workingDirectory = repository.getWorkingDirectory()
                .toAbsolutePath()
                .normalize();

        Index index = new Index(metadataDirectory);

        Map<String, String> stagedFiles =
                new TreeMap<>(index.getEntries());

        if (stagedFiles.isEmpty()) {
            throw new IllegalStateException(
                    "Nothing to commit. Stage files using 'add' first."
            );
        }

        // Verify that staged files have not changed since staging.
        for (Map.Entry<String, String> entry : stagedFiles.entrySet()) {

            Path relativePath = Path.of(entry.getKey());

            if (relativePath.isAbsolute()
                    || relativePath.normalize().startsWith("..")
                    || entry.getKey().contains("\\")) {
                throw new IllegalStateException(
                        "Invalid path in staging index: " + entry.getKey()
                );
            }

            Path file = workingDirectory.resolve(relativePath).normalize();

            if (!file.startsWith(workingDirectory)) {
                throw new IllegalStateException(
                        "Invalid path in staging index: " + entry.getKey()
                );
            }

            if (Index.isDeletion(entry.getValue())) {
                if (Files.exists(file)) {
                    throw new IllegalStateException(
                            "Staged deletion no longer matches the working "
                                    + "tree: " + entry.getKey()
                                    + ". Stage the file again to keep it."
                    );
                }
                continue;
            }

            if (!Files.isRegularFile(file)) {
                throw new IllegalStateException(
                        "Staged file is missing: " + entry.getKey()
                );
            }

            String currentHash = HashUtil.sha256(
                    Files.readAllBytes(file)
            );

            if (!currentHash.equals(entry.getValue())) {
                throw new IllegalStateException(
                        "File changed after staging: " + entry.getKey()
                                + ". Run 'add' again before committing."
                );
            }
        }

        // Resolve HEAD and the current branch reference.
        Path headFile = metadataDirectory.resolve("HEAD");

        String head = Files.readString(
                headFile,
                StandardCharsets.UTF_8
        ).trim();

        if (!head.startsWith("ref: ")) {
            throw new IOException("Invalid HEAD file.");
        }

        String reference = head.substring(5);

        Path refsDirectory = metadataDirectory.resolve("refs")
                .toAbsolutePath()
                .normalize();

        Path branchFile = metadataDirectory.resolve(reference)
                .toAbsolutePath()
                .normalize();

        if (!branchFile.startsWith(refsDirectory)) {
            throw new IOException("Invalid branch reference in HEAD.");
        }

        String parentHash = "";

        if (Files.exists(branchFile)) {
            parentHash = Files.readString(
                    branchFile,
                    StandardCharsets.UTF_8
            ).trim();
        }

        FileObjectStore objectStore =
                new FileObjectStore(metadataDirectory);

        Map<String, String> snapshot = new TreeMap<>(
                new CommitStore(metadataDirectory).readSnapshot(parentHash)
        );

        // Apply staged additions, modifications, and deletions.
        stagedFiles.forEach((path, hash) -> {
            if (Index.isDeletion(hash)) {
                snapshot.remove(path);
            } else {
                snapshot.put(path, hash);
            }
        });

        // Build a commit containing the complete file snapshot.
        StringBuilder content = new StringBuilder();

        content.append("MINIGIT_COMMIT_V2\n");
        content.append("parent=").append(parentHash).append('\n');
        content.append("timestamp=").append(Instant.now()).append('\n');

        String encodedMessage = Base64.getEncoder().encodeToString(
                message.getBytes(StandardCharsets.UTF_8)
        );

        content.append("message=").append(encodedMessage).append('\n');
        content.append("files=").append(snapshot.size()).append('\n');

        for (Map.Entry<String, String> entry : snapshot.entrySet()) {

            String encodedPath = Base64.getEncoder().encodeToString(
                    entry.getKey().getBytes(StandardCharsets.UTF_8)
            );

            content.append(encodedPath)
                    .append('\t')
                    .append(entry.getValue())
                    .append('\n');
        }

        byte[] commitContent = content.toString()
                .getBytes(StandardCharsets.UTF_8);

        // Store the new commit object.
        String commitHash = objectStore.store(commitContent);

        // Prepare the branch reference update.
        Files.createDirectories(branchFile.getParent());

        Path temporaryFile = Files.createTempFile(
                branchFile.getParent(),
                "branch-",
                ".tmp"
        );

        try {
            Files.writeString(
                    temporaryFile,
                    commitHash + "\n",
                    StandardCharsets.UTF_8
            );

            try {
                Files.move(
                        temporaryFile,
                        branchFile,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING
                );
            } catch (IOException e) {
                Files.move(
                        temporaryFile,
                        branchFile,
                        StandardCopyOption.REPLACE_EXISTING
                );
            }

        } finally {
            Files.deleteIfExists(temporaryFile);
        }

        // Record the branch movement so it can be recovered later.
        try {
            Reflog.append(
                    metadataDirectory,
                    "commit",
                    reference.startsWith("refs/heads/")
                            ? reference.substring("refs/heads/".length())
                            : reference,
                    parentHash,
                    commitHash,
                    message
            );
        } catch (IOException e) {
            System.err.println(
                    "Warning: could not write the reflog: "
                            + e.getMessage()
            );
        }

        /*
         * Clear the staging index only after the branch reference
         * has been updated successfully.
         */
        index.clear();

        System.out.println("Commit created successfully!");
        System.out.println("Commit hash: " + commitHash);
        System.out.println(
                "Branch: " + reference.substring("refs/heads/".length())
        );
        System.out.println("Files committed: " + snapshot.size());
        System.out.println("Message: " + message);
    }
}
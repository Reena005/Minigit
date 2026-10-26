package com.minigit.core;

import com.minigit.exception.NothingToUndoException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Module 10 (part 2) - Undo / safe recovery.
 *
 * "Undo last commit" is a soft reset: the current branch moves back to the
 * parent commit and the files the commit changed return to the staging
 * area. Nothing in the working directory changes, and the undone commit
 * stays in the object store and in the reflog, so it can always be
 * recovered with {@code branch <name> <hash>}.
 */
public class UndoManager {

    /** Summary of an undo operation. */
    public record UndoResult(
            String branch,
            String undoneCommit,
            String undoneMessage,
            String newHead,
            List<String> restagedFiles
    ) {
    }

    private final Repository repository;
    private final Path metadata;
    private final CommitStore commitStore;
    private final BranchManager branchManager;

    public UndoManager(Repository repository) {
        this.repository = repository;
        this.metadata = repository.getMetadataDirectory();
        this.commitStore = new CommitStore(metadata);
        this.branchManager = new BranchManager(repository);
    }

    public UndoResult undoLastCommit() throws IOException {

        if (!repository.isInitialized()) {
            throw new IllegalStateException(
                    "MiniGit repository is not initialized. "
                            + "Run 'init' first."
            );
        }

        synchronized (RepositoryLock.LOCK) {

            String branch = branchManager.getCurrentBranch();
            String tip = branchManager.getCurrentCommit();

            if (tip.isEmpty()) {
                throw new NothingToUndoException(
                        "There is no commit to undo on branch '"
                                + branch + "'."
                );
            }

            Index index = new Index(metadata);

            if (!index.getEntries().isEmpty()) {
                throw new IllegalStateException(
                        "Unstage or commit your staged files before "
                                + "undoing a commit."
                );
            }

            CommitStore.CommitData commit = commitStore.readCommit(tip);
            String parent = commit.parent();

            Map<String, String> parentSnapshot =
                    commitStore.readSnapshot(parent);

            Map<String, String> currentSnapshot =
                    commitStore.readSnapshot(tip);

            // Files this commit added, changed, or deleted versus its parent.
            List<String> restaged = new ArrayList<>();

            for (Map.Entry<String, String> entry
                    : currentSnapshot.entrySet()) {

                if (!entry.getValue().equals(
                        parentSnapshot.get(entry.getKey()))) {
                    restaged.add(entry.getKey());
                }
            }
            for (String path : parentSnapshot.keySet()) {
                if (!currentSnapshot.containsKey(path)) {
                    restaged.add(path);
                }
            }

            // 1. Re-stage first: if anything fails later the changes are
            //    still safely in the index.
            for (String path : restaged) {
                String currentHash = currentSnapshot.get(path);
                index.add(
                        path,
                        currentHash == null
                                ? Index.DELETED_ENTRY
                                : currentHash
                );
            }

            // 2. Move the branch back one commit.
            Path branchFile = metadata
                    .resolve("refs").resolve("heads").resolve(branch);

            if (parent.isEmpty()) {
                // Undoing the root commit leaves an empty branch.
                Files.deleteIfExists(branchFile);
            } else {
                writeRef(branchFile, parent);
            }

            // 3. Remember what happened so it can be recovered.
            Reflog.append(
                    metadata, "undo-commit", branch, tip, parent,
                    "Undid commit: " + commit.message()
            );

            return new UndoResult(
                    branch, tip, commit.message(), parent, restaged
            );
        }
    }

    private static void writeRef(Path target, String hash)
            throws IOException {

        Files.createDirectories(target.getParent());

        Path temporary = Files.createTempFile(
                target.getParent(), "ref-", ".tmp"
        );

        try {
            Files.writeString(
                    temporary, hash + "\n", StandardCharsets.UTF_8
            );

            try {
                Files.move(
                        temporary, target,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE
                );
            } catch (IOException e) {
                Files.move(
                        temporary, target,
                        StandardCopyOption.REPLACE_EXISTING
                );
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}

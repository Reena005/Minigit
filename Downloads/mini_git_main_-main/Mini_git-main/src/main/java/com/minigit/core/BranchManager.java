package com.minigit.core;

import com.minigit.exception.BranchNotFoundException;
import com.minigit.exception.CheckoutConflictException;
import com.minigit.storage.FileObjectStore;
import com.minigit.util.HashUtil;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Module 9 - Branch management.
 *
 * Handles listing, creating, deleting and switching branches. A branch is a
 * file in {@code .minigit/refs/heads} that contains the hash of its newest
 * commit; {@code HEAD} points at the current branch.
 *
 * Switching branches is "safe": it refuses to run when it would overwrite
 * or throw away uncommitted work, and it validates every object it needs
 * before touching the working directory.
 */
public class BranchManager {

    private static final String HEADS_PREFIX = "refs/heads/";
    private static final String HEAD_PREFIX = "ref: " + HEADS_PREFIX;

    private static final Pattern BRANCH_NAME =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,99}");

    private static final int MAX_REPORTED_CONFLICTS = 10;

    /** A branch and the commit it points to ("" when it has no commits). */
    public record BranchInfo(String name, String commit, boolean current) {
    }

    /** Summary of a completed checkout. */
    public record CheckoutResult(
            String branch,
            String previousBranch,
            String commit,
            int filesWritten,
            int filesRemoved,
            boolean alreadyOnBranch
    ) {
    }

    private final Repository repository;
    private final Path metadata;
    private final Path headFile;
    private final Path headsDirectory;
    private final FileObjectStore objectStore;
    private final CommitStore commitStore;

    public BranchManager(Repository repository) {
        this.repository = repository;
        this.metadata = repository.getMetadataDirectory();
        this.headFile = metadata.resolve("HEAD");
        this.headsDirectory = metadata.resolve("refs").resolve("heads");
        this.objectStore = new FileObjectStore(metadata);
        this.commitStore = new CommitStore(metadata);
    }

    /* ------------------------------------------------------------
       Validation
    ------------------------------------------------------------ */

    public static boolean isValidBranchName(String name) {
        return name != null
                && BRANCH_NAME.matcher(name).matches()
                && !name.contains("..")
                && !name.endsWith(".")
                && !name.endsWith(".lock")
                && !name.endsWith(".tmp");
    }

    public static void validateBranchName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException(
                    "Branch name cannot be empty."
            );
        }

        if (!isValidBranchName(name)) {
            throw new IllegalArgumentException(
                    "Invalid branch name '" + name + "'. Use letters, "
                            + "numbers, '.', '_' or '-' (up to 100 "
                            + "characters, starting with a letter or "
                            + "number)."
            );
        }
    }

    private void requireInitialized() {
        if (!repository.isInitialized()) {
            throw new IllegalStateException(
                    "MiniGit repository is not initialized. "
                            + "Run 'init' first."
            );
        }
    }

    /* ------------------------------------------------------------
       Reading branches
    ------------------------------------------------------------ */

    public String getCurrentBranch() throws IOException {

        requireInitialized();

        String head = Files.readString(
                headFile, StandardCharsets.UTF_8
        ).trim();

        if (!head.startsWith(HEAD_PREFIX)) {
            throw new IOException("Unsupported HEAD file: " + head);
        }

        String name = head.substring(HEAD_PREFIX.length());

        if (!isValidBranchName(name)) {
            throw new IOException("Invalid branch name in HEAD: " + name);
        }

        return name;
    }

    /** The commit the current branch points to, or "" before the first commit. */
    public String getCurrentCommit() throws IOException {
        return readBranchTip(getCurrentBranch());
    }

    public boolean branchExists(String name) {
        return isValidBranchName(name)
                && Files.isRegularFile(headsDirectory.resolve(name));
    }

    /** Returns the commit hash for a branch, or "" if it has no commits yet. */
    private String readBranchTip(String name) throws IOException {

        Path ref = headsDirectory.resolve(name);

        if (!Files.isRegularFile(ref)) {
            return "";
        }

        String hash = Files.readString(
                ref, StandardCharsets.UTF_8
        ).trim();

        if (!hash.isEmpty() && !CommitStore.isValidHash(hash)) {
            throw new IOException(
                    "Branch '" + name + "' contains an invalid commit hash."
            );
        }

        return hash.toLowerCase();
    }

    public List<BranchInfo> listBranches() throws IOException {

        String current = getCurrentBranch();

        Set<String> names = new TreeSet<>();

        if (Files.isDirectory(headsDirectory)) {
            try (Stream<Path> files = Files.list(headsDirectory)) {
                files.filter(Files::isRegularFile)
                        .map(path -> path.getFileName().toString())
                        .filter(BranchManager::isValidBranchName)
                        .forEach(names::add);
            }
        }

        // The current branch exists even before its first commit.
        names.add(current);

        List<BranchInfo> branches = new ArrayList<>();

        for (String name : names) {
            branches.add(new BranchInfo(
                    name,
                    readBranchTip(name),
                    name.equals(current)
            ));
        }

        return branches;
    }

    /* ------------------------------------------------------------
       Create
    ------------------------------------------------------------ */

    /**
     * Creates a branch.
     *
     * @param startPoint commit to start from; null or blank means the
     *                   current commit. Any commit hash is accepted, which
     *                   makes this the recovery tool for commits found in
     *                   the reflog.
     */
    public BranchInfo createBranch(String name, String startPoint)
            throws IOException {

        requireInitialized();
        validateBranchName(name);

        synchronized (RepositoryLock.LOCK) {

            if (branchExists(name)) {
                throw new IllegalArgumentException(
                        "Branch already exists: " + name
                );
            }

            String commit;

            if (startPoint == null || startPoint.isBlank()) {

                commit = getCurrentCommit();

                if (commit.isEmpty()) {
                    throw new IllegalStateException(
                            "Cannot create a branch before the first "
                                    + "commit."
                    );
                }

            } else {

                commit = startPoint.trim().toLowerCase();

                if (!commitStore.isCommit(commit)) {
                    throw new IllegalArgumentException(
                            "Start point is not a valid commit: "
                                    + startPoint
                    );
                }
            }

            writeBranchTip(name, commit);

            Reflog.append(
                    metadata, "branch-create", name,
                    "", commit, "Created branch " + name
            );

            return new BranchInfo(name, commit, false);
        }
    }

    /* ------------------------------------------------------------
       Delete
    ------------------------------------------------------------ */

    /**
     * Deletes a branch. Without {@code force}, the branch must not contain
     * commits that are unreachable from the current branch.
     */
    public void deleteBranch(String name, boolean force)
            throws IOException {

        requireInitialized();
        validateBranchName(name);

        synchronized (RepositoryLock.LOCK) {

            String current = getCurrentBranch();

            if (name.equals(current)) {
                throw new IllegalStateException(
                        "Cannot delete the branch you are currently on: "
                                + name
                );
            }

            if (!branchExists(name)) {
                throw new BranchNotFoundException(name);
            }

            String tip = readBranchTip(name);

            if (!force && !tip.isEmpty()) {

                String currentCommit = getCurrentCommit();

                if (!commitStore.isAncestor(tip, currentCommit)) {
                    throw new IllegalStateException(
                            "Branch '" + name + "' has commits that are not "
                                    + "on the current branch and would be "
                                    + "lost. Use force to delete it anyway "
                                    + "(tip: " + tip + ")."
                    );
                }
            }

            Files.delete(headsDirectory.resolve(name));

            // Record the old tip so the branch can be recreated later.
            Reflog.append(
                    metadata, "branch-delete", name,
                    tip, "", "Deleted branch " + name
            );
        }
    }

    /* ------------------------------------------------------------
       Checkout
    ------------------------------------------------------------ */

    /** Switches to an existing branch. */
    public CheckoutResult checkout(String name) throws IOException {

        requireInitialized();
        validateBranchName(name);

        synchronized (RepositoryLock.LOCK) {
            return doCheckout(name);
        }
    }

    /** Creates a branch at the current commit and switches to it. */
    public CheckoutResult createAndCheckout(String name)
            throws IOException {
        return createAndCheckout(name, null);
    }

    /** Creates a branch at {@code startPoint} and switches to it. */
    public CheckoutResult createAndCheckout(String name, String startPoint)
            throws IOException {

        requireInitialized();
        validateBranchName(name);

        synchronized (RepositoryLock.LOCK) {

            createBranch(name, startPoint);

            try {
                return doCheckout(name);

            } catch (IOException | RuntimeException e) {
                // Do not leave a half-created branch behind.
                Files.deleteIfExists(headsDirectory.resolve(name));
                throw e;
            }
        }
    }

    private CheckoutResult doCheckout(String target) throws IOException {

        String previousBranch = getCurrentBranch();
        String currentCommit = readBranchTip(previousBranch);

        if (target.equals(previousBranch)) {
            return new CheckoutResult(
                    target, previousBranch, currentCommit, 0, 0, true
            );
        }

        if (!branchExists(target)) {
            throw new BranchNotFoundException(target);
        }

        String targetCommit = readBranchTip(target);

        Map<String, String> staged = new Index(metadata).getEntries();

        int written = 0;
        int removed = 0;

        // Both branches point at the same commit: only HEAD moves and
        // local work (including the staging area) is left untouched.
        if (!currentCommit.equals(targetCommit)) {

            Map<String, String> currentSnapshot =
                    commitStore.readSnapshot(currentCommit);

            Map<String, String> targetSnapshot =
                    commitStore.readSnapshot(targetCommit);

            // Paths whose committed content differs between the branches.
            Set<String> changed = new TreeSet<>();
            changed.addAll(currentSnapshot.keySet());
            changed.addAll(targetSnapshot.keySet());
            changed.removeIf(path -> Objects.equals(
                    currentSnapshot.get(path),
                    targetSnapshot.get(path)
            ));

            List<String> conflicts = findConflicts(
                    changed, currentSnapshot, targetSnapshot, staged
            );

            if (!conflicts.isEmpty()) {
                throw new CheckoutConflictException(
                        "Cannot switch to '" + target + "': your local "
                                + "changes would be overwritten. Commit, "
                                + "unstage or restore these files first: "
                                + summarize(conflicts),
                        conflicts
                );
            }

            // Read and verify every object before touching the working tree.
            Map<String, byte[]> contents = new HashMap<>();

            for (String path : changed) {
                String blobHash = targetSnapshot.get(path);

                if (blobHash != null) {
                    contents.put(path, objectStore.read(blobHash));
                }
            }

            Path workingDirectory = repository.getWorkingDirectory();

            for (String path : changed) {

                Path file = resolveInside(workingDirectory, path);

                if (contents.containsKey(path)) {
                    writeFile(file, contents.get(path));
                    written++;

                } else if (Files.deleteIfExists(file)) {
                    removeEmptyParents(file.getParent(), workingDirectory);
                    removed++;
                }
            }

            // Staged entries (if any) were already identical to the old
            // snapshot and are stale for the new one.
            new Index(metadata).clear();
        }

        writeHead(target);

        Reflog.append(
                metadata, "checkout", target,
                currentCommit, targetCommit,
                "Switched from " + previousBranch + " to " + target
        );

        return new CheckoutResult(
                target, previousBranch, targetCommit,
                written, removed, false
        );
    }

    private List<String> findConflicts(
            Set<String> changed,
            Map<String, String> currentSnapshot,
            Map<String, String> targetSnapshot,
            Map<String, String> staged
    ) throws IOException {

        List<String> conflicts = new ArrayList<>();
        Path workingDirectory = repository.getWorkingDirectory();

        // Staged work that is not part of the current commit.
        for (Map.Entry<String, String> entry : staged.entrySet()) {
            if (!entry.getValue().equals(
                    currentSnapshot.get(entry.getKey()))) {
                conflicts.add(entry.getKey() + " (staged)");
            }
        }

        for (String path : changed) {

            Path file = resolveInside(workingDirectory, path);

            String committedNow = currentSnapshot.get(path);
            String committedTarget = targetSnapshot.get(path);

            if (Files.isDirectory(file)) {
                if (committedTarget != null) {
                    conflicts.add(path + " (a folder is in the way)");
                }
                continue;
            }

            boolean exists = Files.isRegularFile(file);

            if (committedNow != null) {

                // Tracked on the current branch.
                if (!exists) {
                    if (committedTarget != null) {
                        conflicts.add(path + " (deleted locally)");
                    }
                } else if (!committedNow.equals(hashOf(file))) {
                    conflicts.add(path + " (modified)");
                }

            } else if (exists
                    && !Objects.equals(committedTarget, hashOf(file))) {

                // Untracked here but would be overwritten by the target.
                conflicts.add(path + " (untracked file would be overwritten)");
            }
        }

        return conflicts;
    }

    /* ------------------------------------------------------------
       File helpers
    ------------------------------------------------------------ */

    private static String hashOf(Path file) throws IOException {
        return HashUtil.sha256(Files.readAllBytes(file));
    }

    private static String summarize(List<String> conflicts) {

        if (conflicts.size() <= MAX_REPORTED_CONFLICTS) {
            return String.join(", ", conflicts);
        }

        return String.join(
                ", ", conflicts.subList(0, MAX_REPORTED_CONFLICTS)
        ) + " and " + (conflicts.size() - MAX_REPORTED_CONFLICTS)
                + " more";
    }

    private Path resolveInside(Path workingDirectory, String relative)
            throws IOException {

        Path file = workingDirectory.resolve(relative).normalize();

        if (!file.startsWith(workingDirectory)
                || file.startsWith(repository.getMetadataDirectory())) {
            throw new IOException("Unsafe path in commit: " + relative);
        }

        return file;
    }

    private static void writeFile(Path file, byte[] content)
            throws IOException {

        Files.createDirectories(file.getParent());

        Path temporary = Files.createTempFile(
                file.getParent(), "checkout-", ".tmp"
        );

        try {
            Files.write(temporary, content);
            Files.move(
                    temporary, file, StandardCopyOption.REPLACE_EXISTING
            );
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /** Removes now-empty directories left behind by a deleted file. */
    private static void removeEmptyParents(Path directory, Path stopAt)
            throws IOException {

        Path current = directory;

        while (current != null
                && !current.equals(stopAt)
                && current.startsWith(stopAt)) {

            try (DirectoryStream<Path> children =
                         Files.newDirectoryStream(current)) {

                if (children.iterator().hasNext()) {
                    return;
                }
            }

            Files.delete(current);
            current = current.getParent();
        }
    }

    private void writeBranchTip(String name, String commit)
            throws IOException {

        Files.createDirectories(headsDirectory);
        atomicWrite(headsDirectory.resolve(name), commit + "\n");
    }

    private void writeHead(String branch) throws IOException {
        atomicWrite(headFile, HEAD_PREFIX + branch + "\n");
    }

    private static void atomicWrite(Path target, String content)
            throws IOException {

        Path temporary = Files.createTempFile(
                target.getParent(), "ref-", ".tmp"
        );

        try {
            Files.writeString(temporary, content, StandardCharsets.UTF_8);

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

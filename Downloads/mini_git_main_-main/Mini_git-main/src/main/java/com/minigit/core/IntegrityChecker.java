package com.minigit.core;

import com.minigit.storage.FileObjectStore;
import com.minigit.util.HashUtil;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Read-only verification of stored objects and commit references. */
public class IntegrityChecker {

    private static final Pattern OBJECT_HASH = Pattern.compile("[0-9a-f]{64}");

    public record FailedCheck(
            String objectHash,
            String status,
            String reason
    ) {
    }

    public record IntegrityReport(
            boolean success,
            int totalObjectsChecked,
            int validObjectCount,
            int corruptedObjectCount,
            int missingObjectCount,
            int unreadableObjectCount,
            String status,
            List<FailedCheck> results
    ) {
    }

    private final Repository repository;

    public IntegrityChecker(Repository repository) {
        this.repository = repository;
    }

    public IntegrityReport check() throws IOException {
        if (!repository.isInitialized()) {
            throw new IllegalStateException(
                    "MiniGit repository is not initialized."
            );
        }

        Path metadata = repository.getMetadataDirectory();
        Path objectsDirectory = metadata.resolve("objects");
        FileObjectStore objectStore = new FileObjectStore(metadata);
        CommitStore commitStore = new CommitStore(metadata);
        List<FailedCheck> failures = new ArrayList<>();
        Set<String> checkedHashes = new HashSet<>();
        Set<String> validHashes = new HashSet<>();
        Set<String> corruptHashes = new HashSet<>();
        Set<String> unreadableHashes = new HashSet<>();
        Set<String> missingHashes = new HashSet<>();
        Set<String> discoveredCommitHashes = new HashSet<>();

        try (Stream<Path> objects = Files.list(objectsDirectory)) {
            for (Path objectPath : objects.toList()) {
                String name = objectPath.getFileName().toString();
                checkedHashes.add(name);

                if (!OBJECT_HASH.matcher(name).matches()) {
                    corruptHashes.add(name);
                    failures.add(new FailedCheck(
                            name,
                            "CORRUPTED",
                            "Object filename is not a lowercase SHA-256 hash."
                    ));
                    continue;
                }

                boolean regularFile;
                try {
                    regularFile = Files.isRegularFile(
                            objectPath,
                            LinkOption.NOFOLLOW_LINKS
                    );
                } catch (SecurityException e) {
                    unreadableHashes.add(name);
                    failures.add(new FailedCheck(
                            name,
                            "UNREADABLE",
                            "Unable to inspect object: " + safeMessage(e)
                    ));
                    continue;
                }

                if (!regularFile) {
                    unreadableHashes.add(name);
                    failures.add(new FailedCheck(
                            name,
                            "UNREADABLE",
                            "Object is not a readable regular file."
                    ));
                    continue;
                }

                byte[] content;
                try {
                    content = Files.readAllBytes(objectPath);
                } catch (IOException | SecurityException e) {
                    unreadableHashes.add(name);
                    failures.add(new FailedCheck(
                            name,
                            "UNREADABLE",
                            "Unable to read object: " + safeMessage(e)
                    ));
                    continue;
                }

                if (!HashUtil.sha256(content).equals(name)) {
                    corruptHashes.add(name);
                    failures.add(new FailedCheck(
                            name,
                            "CORRUPTED",
                            "Content SHA-256 does not match the object filename."
                    ));
                    continue;
                }

                validHashes.add(name);
                if (isCommitObject(content)) {
                    discoveredCommitHashes.add(name);
                }
            }
        } catch (SecurityException e) {
            throw new IOException(
                    "Unable to list the MiniGit objects directory: "
                            + safeMessage(e),
                    e
            );
        }

        Deque<String> pendingCommits =
                new ArrayDeque<>(discoveredCommitHashes);
        Set<String> visitedCommits = new HashSet<>();

        while (!pendingCommits.isEmpty()) {
            String hash = pendingCommits.removeFirst();

            if (!visitedCommits.add(hash) || !validHashes.contains(hash)) {
                continue;
            }

            CommitStore.CommitData commit;
            try {
                commit = commitStore.readCommit(hash);
            } catch (IOException | IllegalArgumentException e) {
                validHashes.remove(hash);
                corruptHashes.add(hash);
                failures.add(new FailedCheck(
                        hash,
                        "CORRUPTED",
                        "Malformed commit metadata: " + safeMessage(e)
                ));
                continue;
            }

            if (!commit.parent().isEmpty()) {
                checkReference(
                        commit.parent(),
                        "Missing parent commit object.",
                        objectStore,
                        checkedHashes,
                        validHashes,
                        corruptHashes,
                        missingHashes,
                        unreadableHashes,
                        failures
                );
                if (validHashes.contains(commit.parent())) {
                    pendingCommits.add(commit.parent());
                }
            }

            for (Map.Entry<String, String> file : commit.files().entrySet()) {
                checkReference(
                        file.getValue(),
                        "Missing file blob referenced by " + file.getKey() + ".",
                        objectStore,
                        checkedHashes,
                        validHashes,
                        corruptHashes,
                        missingHashes,
                        unreadableHashes,
                        failures
                );
            }
        }

        checkRepositoryReferences(
                metadata,
                objectStore,
                checkedHashes,
                validHashes,
                corruptHashes,
                missingHashes,
                unreadableHashes,
                failures
        );

        int validCount = validHashes.size();
        int corruptedCount = corruptHashes.size();
        int missingCount = missingHashes.size();
        int unreadableCount = unreadableHashes.size();
        int total = validCount + corruptedCount + missingCount
                + unreadableCount;
        String status = corruptedCount == 0
                && missingCount == 0
                && unreadableCount == 0
                ? "OK"
                : "ISSUES_FOUND";

        return new IntegrityReport(
                true,
                total,
                validCount,
                corruptedCount,
                missingCount,
                unreadableCount,
                status,
                List.copyOf(failures)
        );
    }

    private void checkRepositoryReferences(
            Path metadata,
            FileObjectStore objectStore,
            Set<String> checkedHashes,
            Set<String> validHashes,
            Set<String> corruptHashes,
            Set<String> missingHashes,
            Set<String> unreadableHashes,
            List<FailedCheck> failures
    ) {
        Path headFile = metadata.resolve("HEAD");
        String head;
        try {
            head = Files.readString(headFile, StandardCharsets.UTF_8).trim();
        } catch (IOException | SecurityException e) {
            recordMetadataFailure(
                    "HEAD",
                    "UNREADABLE",
                    "Unable to read repository HEAD: " + safeMessage(e),
                    checkedHashes,
                    unreadableHashes,
                    failures
            );
            return;
        }

        String prefix = "ref: refs/heads/";
        if (!head.startsWith(prefix) || head.length() == prefix.length()) {
            recordMetadataFailure(
                    "HEAD",
                    "CORRUPTED",
                    "HEAD does not contain a valid branch reference.",
                    checkedHashes,
                    corruptHashes,
                    failures
            );
            return;
        }

        Path headsDirectory = metadata.resolve("refs").resolve("heads");
        try (Stream<Path> refs = Files.list(headsDirectory)) {
            for (Path ref : refs.toList()) {
                if (!Files.isRegularFile(ref, LinkOption.NOFOLLOW_LINKS)) {
                    recordMetadataFailure(
                            ref.getFileName().toString(),
                            "UNREADABLE",
                            "Branch reference is not a readable regular file.",
                            checkedHashes,
                            unreadableHashes,
                            failures
                    );
                    continue;
                }

                String name = "refs/heads/" + ref.getFileName();
                String hash;
                try {
                    hash = Files.readString(ref, StandardCharsets.UTF_8)
                            .trim();
                } catch (IOException | SecurityException e) {
                    recordMetadataFailure(
                            name,
                            "UNREADABLE",
                            "Unable to read branch reference: "
                                    + safeMessage(e),
                            checkedHashes,
                            unreadableHashes,
                            failures
                    );
                    continue;
                }

                if (!CommitStore.isValidHash(hash)) {
                    recordMetadataFailure(
                            name,
                            "CORRUPTED",
                            "Branch reference does not contain a valid "
                                    + "commit hash.",
                            checkedHashes,
                            corruptHashes,
                            failures
                    );
                    continue;
                }

                checkReference(
                        hash,
                        "Branch reference points to a missing commit object.",
                        objectStore,
                        checkedHashes,
                        validHashes,
                        corruptHashes,
                        missingHashes,
                        unreadableHashes,
                        failures
                );
            }
        } catch (IOException | SecurityException e) {
            recordMetadataFailure(
                    "refs/heads",
                    "UNREADABLE",
                    "Unable to list branch references: " + safeMessage(e),
                    checkedHashes,
                    unreadableHashes,
                    failures
            );
        }
    }

    private static void recordMetadataFailure(
            String name,
            String status,
            String reason,
            Set<String> checkedHashes,
            Set<String> category,
            List<FailedCheck> failures
    ) {
        if (checkedHashes.add(name)) {
            category.add(name);
            failures.add(new FailedCheck(name, status, reason));
        }
    }

    private void checkReference(
            String hash,
            String missingReason,
            FileObjectStore objectStore,
            Set<String> checkedHashes,
            Set<String> validHashes,
            Set<String> corruptHashes,
            Set<String> missingHashes,
            Set<String> unreadableHashes,
            List<FailedCheck> failures
    ) {
        if (!CommitStore.isValidHash(hash)) {
            if (corruptHashes.add(hash)) {
                checkedHashes.add(hash);
                failures.add(new FailedCheck(
                        hash,
                        "CORRUPTED",
                        "Commit metadata contains an invalid object hash."
                ));
            }
            return;
        }

        String normalizedHash = hash.toLowerCase();
        if (checkedHashes.contains(normalizedHash)) {
            return;
        }

        checkedHashes.add(normalizedHash);
        try {
            objectStore.read(normalizedHash);
            validHashes.add(normalizedHash);
        } catch (IOException | SecurityException e) {
            Path objectPath = repository.getMetadataDirectory()
                    .resolve("objects")
                    .resolve(normalizedHash);
            boolean exists;
            try {
                exists = Files.exists(objectPath, LinkOption.NOFOLLOW_LINKS);
            } catch (SecurityException inspectionError) {
                unreadableHashes.add(hash);
                failures.add(new FailedCheck(
                        hash,
                        "UNREADABLE",
                        "Unable to inspect referenced object: "
                                + safeMessage(inspectionError)
                ));
                return;
            }
            if (!exists) {
                missingHashes.add(normalizedHash);
                failures.add(new FailedCheck(
                        normalizedHash,
                        "MISSING",
                        missingReason
                ));
            } else {
                unreadableHashes.add(normalizedHash);
                failures.add(new FailedCheck(
                        normalizedHash,
                        "UNREADABLE",
                        "Unable to read referenced object: " + safeMessage(e)
                ));
            }
        }
    }

    private static boolean isCommitObject(byte[] content) {
        int end = 0;
        while (end < content.length && content[end] != '\n') {
            end++;
        }
        String header = new String(
                content,
                0,
                end,
                StandardCharsets.UTF_8
        );
        return "MINIGIT_COMMIT_V1".equals(header)
                || "MINIGIT_COMMIT_V2".equals(header);
    }

    private static String safeMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank()
                ? exception.getClass().getSimpleName()
                : message;
    }
}

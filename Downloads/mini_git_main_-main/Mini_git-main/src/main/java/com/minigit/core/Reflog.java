package com.minigit.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;

/**
 * A simple append-only log of every change to a branch tip.
 *
 * The reflog is MiniGit's safety net: even after "undo" or after a branch
 * is deleted, the previous commit hash is recorded here and can be turned
 * back into a branch.
 *
 * Line format (tab separated):
 * timestamp, action, branch, oldHash, newHash, base64(detail)
 */
public final class Reflog {

    public static final String FILE_NAME = "reflog";

    /** One line of the reflog. */
    public record Entry(
            String timestamp,
            String action,
            String branch,
            String oldHash,
            String newHash,
            String detail
    ) {
    }

    private Reflog() {
        // Prevent instantiation.
    }

    public static void append(
            Path metadataDirectory,
            String action,
            String branch,
            String oldHash,
            String newHash,
            String detail
    ) throws IOException {

        Path logsDirectory = metadataDirectory.resolve("logs");
        Files.createDirectories(logsDirectory);

        String encodedDetail = Base64.getEncoder().encodeToString(
                (detail == null ? "" : detail)
                        .getBytes(StandardCharsets.UTF_8)
        );

        String line = String.join("\t",
                Instant.now().toString(),
                clean(action),
                clean(branch),
                oldHash == null ? "" : oldHash,
                newHash == null ? "" : newHash,
                encodedDetail
        ) + "\n";

        synchronized (RepositoryLock.LOCK) {
            Files.writeString(
                    logsDirectory.resolve(FILE_NAME),
                    line,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND
            );
        }
    }

    /** Returns up to {@code limit} entries, newest first. */
    public static List<Entry> read(Path metadataDirectory, int limit)
            throws IOException {

        Path file = metadataDirectory.resolve("logs").resolve(FILE_NAME);

        if (!Files.isRegularFile(file)) {
            return new ArrayList<>();
        }

        List<Entry> entries = new ArrayList<>();

        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {

            if (line.isBlank()) {
                continue;
            }

            String[] parts = line.split("\t", -1);

            if (parts.length != 6) {
                continue; // Ignore malformed lines instead of failing.
            }

            String detail;

            try {
                detail = new String(
                        Base64.getDecoder().decode(parts[5]),
                        StandardCharsets.UTF_8
                );
            } catch (IllegalArgumentException e) {
                detail = "";
            }

            entries.add(new Entry(
                    parts[0], parts[1], parts[2],
                    parts[3], parts[4], detail
            ));
        }

        Collections.reverse(entries);

        if (limit > 0 && entries.size() > limit) {
            return new ArrayList<>(entries.subList(0, limit));
        }

        return entries;
    }

    private static String clean(String value) {
        return value == null
                ? ""
                : value.replace('\t', ' ').replace('\n', ' ');
    }
}

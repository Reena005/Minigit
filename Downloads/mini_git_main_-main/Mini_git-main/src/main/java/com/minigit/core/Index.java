package com.minigit.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Base64;
import java.util.Map;
import java.util.TreeMap;

public class Index {

    public static final String DELETED_ENTRY = "DELETED";

    private final Path indexFile;
    private final Map<String, String> entries = new TreeMap<>();

    public Index(Path repositoryMetadataDirectory) throws IOException {

        this.indexFile = repositoryMetadataDirectory.resolve("index");

        if (!Files.isRegularFile(indexFile)) {
            throw new IOException(
                    "MiniGit index file not found: " + indexFile
            );
        }

        load();
    }

    private void load() throws IOException {

        entries.clear();

        for (String line : Files.readAllLines(
                indexFile,
                StandardCharsets.UTF_8
        )) {
            if (line.isBlank()) {
                continue;
            }

            String[] parts = line.split("\\t", 2);

            if (parts.length != 2) {
                throw new IOException("Invalid entry in MiniGit index.");
            }

            try {
                String path = new String(
                        Base64.getDecoder().decode(parts[0]),
                        StandardCharsets.UTF_8
                );

                String hash = parts[1];

                entries.put(path, hash);

            } catch (IllegalArgumentException e) {
                throw new IOException(
                        "Could not decode a MiniGit index entry.",
                        e
                );
            }
        }
    }

    public void add(String relativePath, String objectHash)
            throws IOException {

        if (relativePath == null || relativePath.isBlank()) {
            throw new IllegalArgumentException(
                    "File path cannot be empty."
            );
        }

        if (objectHash == null
                || (!DELETED_ENTRY.equals(objectHash)
                && !objectHash.matches("[0-9a-f]{64}"))) {
            throw new IllegalArgumentException(
                    "Invalid object hash or deletion marker."
            );
        }

        entries.put(relativePath, objectHash);

        save();
    }

    private void save() throws IOException {

        StringBuilder content = new StringBuilder();

        for (Map.Entry<String, String> entry : entries.entrySet()) {

            String encodedPath = Base64.getEncoder().encodeToString(
                    entry.getKey().getBytes(StandardCharsets.UTF_8)
            );

            content.append(encodedPath)
                    .append('\t')
                    .append(entry.getValue())
                    .append('\n');
        }

        Path temporaryFile = Files.createTempFile(
                indexFile.getParent(),
                "index-",
                ".tmp"
        );

        try {
            Files.writeString(
                    temporaryFile,
                    content.toString(),
                    StandardCharsets.UTF_8
            );

            try {
                Files.move(
                        temporaryFile,
                        indexFile,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE
                );
            } catch (IOException e) {
                Files.move(
                        temporaryFile,
                        indexFile,
                        StandardCopyOption.REPLACE_EXISTING
                );
            }

        } finally {
            Files.deleteIfExists(temporaryFile);
        }
    }

    public Map<String, String> getEntries() {
        return Map.copyOf(entries);
    }

    public static boolean isDeletion(String entry) {
        return DELETED_ENTRY.equals(entry);
    }

    public void clear() throws IOException {
        entries.clear();
        save();
    }

    /**
     * Removes a single path from the staging area.
     *
     * @return true if the path was staged and has been removed
     */
    public boolean remove(String relativePath) throws IOException {

        if (relativePath == null || !entries.containsKey(relativePath)) {
            return false;
        }

        entries.remove(relativePath);
        save();

        return true;
    }
}
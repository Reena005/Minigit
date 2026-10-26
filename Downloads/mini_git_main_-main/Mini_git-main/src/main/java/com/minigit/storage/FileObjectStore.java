package com.minigit.storage;

import com.minigit.model.Blob;
import com.minigit.util.HashUtil;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public class FileObjectStore implements ObjectStore {

    private final Path objectsDirectory;

    public FileObjectStore(Path repositoryMetadataDirectory) {
        this.objectsDirectory =
                repositoryMetadataDirectory.resolve("objects");
    }

    @Override
    public String store(byte[] content) throws IOException {

        Blob blob = new Blob(content);
        String hash = blob.getHash();

        Path objectPath = getObjectPath(hash);

        Files.createDirectories(objectsDirectory);

        if (!Files.exists(objectPath)) {
            Path temporaryFile = Files.createTempFile(
                    objectsDirectory,
                    "object-",
                    ".tmp"
            );

            try {
                Files.write(temporaryFile, blob.getContent());

                try {
                    Files.move(
                            temporaryFile,
                            objectPath,
                            StandardCopyOption.ATOMIC_MOVE
                    );
                } catch (IOException e) {
                    if (Files.exists(objectPath)) {
                        Files.deleteIfExists(temporaryFile);
                    } else {
                        throw e;
                    }
                }

            } finally {
                Files.deleteIfExists(temporaryFile);
            }
        }

        return hash;
    }

    @Override
    public byte[] read(String hash) throws IOException {

        Path objectPath = getObjectPath(hash);

        if (!Files.isRegularFile(objectPath)) {
            throw new IOException("Object not found: " + hash);
        }

        byte[] content = Files.readAllBytes(objectPath);

        if (!HashUtil.sha256(content).equals(hash)) {
            throw new IOException(
                    "Object integrity check failed: " + hash
            );
        }

        return content;
    }

    @Override
    public boolean exists(String hash) {
        try {
            return Files.isRegularFile(getObjectPath(hash));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private Path getObjectPath(String hash) {

        if (hash == null || !hash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                    "Invalid SHA-256 object hash."
            );
        }

        return objectsDirectory.resolve(hash);
    }
}
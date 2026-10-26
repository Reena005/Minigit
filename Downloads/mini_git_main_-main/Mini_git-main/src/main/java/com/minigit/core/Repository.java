package com.minigit.core;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
public class Repository {
    private final Path workingDirectory;
    private final Path metadataDirectory;
    public Repository(Path workingDirectory) {
        this.workingDirectory = workingDirectory
                .toAbsolutePath()
                .normalize();
        this.metadataDirectory =
                this.workingDirectory.resolve(".minigit");
    }
    public void init() throws IOException {
        if (!Files.isDirectory(workingDirectory)) {
            throw new IllegalArgumentException(
                    "Working directory does not exist: "
                            + workingDirectory
            );
        }
        if (Files.exists(metadataDirectory)) {
            throw new IllegalArgumentException(
                    "A MiniGit repository already exists at: "
                            + metadataDirectory
            );
        }
        Files.createDirectory(metadataDirectory);
        Files.createDirectories(
                metadataDirectory.resolve("objects")
        );
        Files.createDirectories(
                metadataDirectory.resolve("refs").resolve("heads")
        );
        Files.writeString(
                metadataDirectory.resolve("HEAD"),
                "ref: refs/heads/main\n",
                StandardCharsets.UTF_8
        );
        Files.writeString(
                metadataDirectory.resolve("index"),
                "",
                StandardCharsets.UTF_8
        );
    }
    public Path getWorkingDirectory() {
        return workingDirectory;
    }
    public Path getMetadataDirectory() {
        return metadataDirectory;
    }
    public boolean isInitialized() {
        return Files.isDirectory(metadataDirectory)
                && Files.isDirectory(
                        metadataDirectory.resolve("objects")
                )
                && Files.isRegularFile(
                        metadataDirectory.resolve("HEAD")
                )
                && Files.isRegularFile(
                        metadataDirectory.resolve("index")
                );
    }
}

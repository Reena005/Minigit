package com.minigit.core;

import com.minigit.command.AddCommand;
import com.minigit.command.CommitCommand;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Small helper that builds repositories for the tests. */
final class TestRepository {

    final Path directory;
    final Repository repository;

    TestRepository(Path directory) throws IOException {
        this.directory = directory;
        this.repository = new Repository(directory);
        this.repository.init();
    }

    void write(String relativePath, String content) throws IOException {
        Path file = directory.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    String read(String relativePath) throws IOException {
        return Files.readString(
                directory.resolve(relativePath), StandardCharsets.UTF_8
        );
    }

    boolean exists(String relativePath) {
        return Files.exists(directory.resolve(relativePath));
    }

    void stage(String relativePath) throws IOException {
        new AddCommand(repository).execute(relativePath);
    }

    /** Stages the given files, commits them and returns the commit hash. */
    String commit(String message, String... files) throws IOException {
        for (String file : files) {
            stage(file);
        }
        new CommitCommand(repository).execute(message);
        return new BranchManager(repository).getCurrentCommit();
    }
}

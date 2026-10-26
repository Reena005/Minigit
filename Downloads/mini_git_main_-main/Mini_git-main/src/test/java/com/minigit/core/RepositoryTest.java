package com.minigit.core;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;
class RepositoryTest {
    @TempDir
    Path temporaryDirectory;
    @Test
    void initializesRepositoryStructure() throws IOException {
        Repository repository = new Repository(temporaryDirectory);
        repository.init();
        Path metadata = temporaryDirectory.resolve(".minigit");
        assertTrue(Files.isDirectory(metadata));
        assertTrue(
                Files.isDirectory(metadata.resolve("objects"))
        );
        assertTrue(
                Files.isDirectory(
                        metadata.resolve("refs").resolve("heads")
                )
        );
        assertTrue(
                Files.isRegularFile(metadata.resolve("HEAD"))
        );
        assertTrue(
                Files.isRegularFile(metadata.resolve("index"))
        );
        assertTrue(repository.isInitialized());
    }
    @Test
    void refusesToInitializeTwice() throws IOException {
        Repository repository = new Repository(temporaryDirectory);
        repository.init();
        assertThrows(
                IllegalArgumentException.class,
                repository::init
        );
    }
}

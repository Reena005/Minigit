package com.minigit.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

class StagingManagerTest {

    @TempDir
    java.nio.file.Path temporaryDirectory;

    @Test
    void unstageRemovesOnlyThatFileAndKeepsItOnDisk() throws IOException {
        TestRepository repo = new TestRepository(temporaryDirectory);
        repo.write("a.txt", "one");
        repo.write("b.txt", "two");
        repo.stage("a.txt");
        repo.stage("b.txt");

        StagingManager staging = new StagingManager(repo.repository);
        assertEquals("a.txt", staging.unstage("a.txt"));

        assertEquals(1, staging.getStagedFiles().size());
        assertTrue(staging.getStagedFiles().containsKey("b.txt"));
        assertEquals("one", repo.read("a.txt"));
    }

    @Test
    void unstagingAFileThatIsNotStagedFails() throws IOException {
        TestRepository repo = new TestRepository(temporaryDirectory);
        repo.write("a.txt", "one");

        StagingManager staging = new StagingManager(repo.repository);

        assertThrows(
                IllegalArgumentException.class,
                () -> staging.unstage("a.txt")
        );
    }

    @Test
    void rejectsEmptyAbsoluteAndEscapingPaths() throws IOException {
        TestRepository repo = new TestRepository(temporaryDirectory);
        StagingManager staging = new StagingManager(repo.repository);

        for (String bad : new String[]{
                null, "", "  ", "/etc/passwd", "../outside.txt",
                "a/../../outside.txt", "..", ".", "win\\path"}) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> staging.unstage(bad),
                    "should reject '" + bad + "'"
            );
        }
    }

    @Test
    void normalisesPathsBeforeLookingThemUp() throws IOException {
        TestRepository repo = new TestRepository(temporaryDirectory);
        repo.write("docs/readme.txt", "hello");
        repo.stage("docs/readme.txt");

        StagingManager staging = new StagingManager(repo.repository);

        assertEquals(
                "docs/readme.txt",
                staging.unstage("./docs//readme.txt")
        );
        assertTrue(staging.getStagedFiles().isEmpty());
    }

    @Test
    void unstageAllEmptiesTheIndex() throws IOException {
        TestRepository repo = new TestRepository(temporaryDirectory);
        repo.write("a.txt", "one");
        repo.write("b.txt", "two");
        repo.stage("a.txt");
        repo.stage("b.txt");

        StagingManager staging = new StagingManager(repo.repository);

        assertEquals(2, staging.unstageAll());
        assertTrue(staging.getStagedFiles().isEmpty());
        assertThrows(IllegalStateException.class, staging::unstageAll);
    }

    @Test
    void unstagedFileCanBeStagedAgain() throws IOException {
        TestRepository repo = new TestRepository(temporaryDirectory);
        repo.write("a.txt", "one");
        repo.stage("a.txt");

        StagingManager staging = new StagingManager(repo.repository);
        staging.unstage("a.txt");
        repo.stage("a.txt");

        assertEquals(1, staging.getStagedFiles().size());
    }

    @Test
    void failsOutsideAnInitializedRepository() {
        StagingManager staging = new StagingManager(
                new Repository(temporaryDirectory)
        );

        assertThrows(IllegalStateException.class, staging::getStagedFiles);
    }
}

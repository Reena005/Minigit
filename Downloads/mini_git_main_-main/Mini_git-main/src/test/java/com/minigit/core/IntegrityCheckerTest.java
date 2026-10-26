package com.minigit.core;

import com.minigit.storage.FileObjectStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class IntegrityCheckerTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void reportsAllObjectsValidForIntactRepository() throws IOException {
        TestRepository repo = new TestRepository(temporaryDirectory);
        repo.write("hello.txt", "intact content");
        repo.commit("add file", "hello.txt");

        IntegrityChecker.IntegrityReport report =
                new IntegrityChecker(repo.repository).check();

        assertTrue(report.success());
        assertEquals("OK", report.status());
        assertEquals(2, report.totalObjectsChecked());
        assertEquals(2, report.validObjectCount());
        assertEquals(0, report.corruptedObjectCount());
        assertEquals(0, report.missingObjectCount());
        assertEquals(0, report.unreadableObjectCount());
        assertTrue(report.results().isEmpty());
    }

    @Test
    void detectsBlobContentThatDoesNotMatchItsObjectHash()
            throws IOException {
        TestRepository repo = committedFile();
        String blobHash = blobHash(repo);
        Files.writeString(
                objectPath(repo, blobHash),
                "corrupt content",
                StandardCharsets.UTF_8
        );

        IntegrityChecker.IntegrityReport report =
                new IntegrityChecker(repo.repository).check();

        assertEquals(1, report.corruptedObjectCount());
        assertEquals(0, report.missingObjectCount());
        assertTrue(report.results().stream().anyMatch(result ->
                result.objectHash().equals(blobHash)
                        && "CORRUPTED".equals(result.status())
        ));
    }

    @Test
    void detectsMissingBlobReferencedByACommit() throws IOException {
        TestRepository repo = committedFile();
        String blobHash = blobHash(repo);
        Files.delete(objectPath(repo, blobHash));

        IntegrityChecker.IntegrityReport report =
                new IntegrityChecker(repo.repository).check();

        assertEquals(1, report.missingObjectCount());
        assertEquals(1, report.validObjectCount());
        assertTrue(report.results().stream().anyMatch(result ->
                result.objectHash().equals(blobHash)
                        && "MISSING".equals(result.status())
        ));
    }

    @Test
    void treatsAnUnreadableObjectEntryAsUnreadable() throws IOException {
        TestRepository repo = new TestRepository(temporaryDirectory);
        String hash = "a".repeat(64);
        Files.createDirectory(objectPath(repo, hash));

        IntegrityChecker.IntegrityReport report =
                new IntegrityChecker(repo.repository).check();

        assertEquals(1, report.unreadableObjectCount());
        assertEquals("UNREADABLE", report.results().get(0).status());
        assertEquals(hash, report.results().get(0).objectHash());
    }

    @Test
    void detectsMalformedCommitMetadataEvenWhenItsHashIsValid()
            throws IOException {
        TestRepository repo = new TestRepository(temporaryDirectory);
        FileObjectStore objects = new FileObjectStore(
                repo.repository.getMetadataDirectory()
        );
        String malformedCommitHash = objects.store(
                "MINIGIT_COMMIT_V2\nnot-valid-metadata\n"
                        .getBytes(StandardCharsets.UTF_8)
        );

        IntegrityChecker.IntegrityReport report =
                new IntegrityChecker(repo.repository).check();

        assertEquals(1, report.corruptedObjectCount());
        assertTrue(report.results().stream().anyMatch(result ->
                result.objectHash().equals(malformedCommitHash)
                        && result.reason().contains("Malformed commit metadata")
        ));
    }

    @Test
    void reportsInitializedEmptyRepositoryAsClean() throws IOException {
        TestRepository repo = new TestRepository(temporaryDirectory);

        IntegrityChecker.IntegrityReport report =
                new IntegrityChecker(repo.repository).check();

        assertTrue(report.success());
        assertEquals("OK", report.status());
        assertEquals(0, report.totalObjectsChecked());
        assertEquals(0, report.validObjectCount());
        assertTrue(report.results().isEmpty());
    }

    @Test
    void detectsMalformedBranchReferenceMetadata() throws IOException {
        TestRepository repo = new TestRepository(temporaryDirectory);
        Path branchRef = repo.repository.getMetadataDirectory()
                .resolve("refs")
                .resolve("heads")
                .resolve("main");
        Files.writeString(branchRef, "not-a-commit-hash\n");

        IntegrityChecker.IntegrityReport report =
                new IntegrityChecker(repo.repository).check();

        assertEquals("ISSUES_FOUND", report.status());
        assertEquals(1, report.corruptedObjectCount());
        assertTrue(report.results().stream().anyMatch(result ->
                result.objectHash().equals("refs/heads/main")
                        && "CORRUPTED".equals(result.status())
        ));
        assertEquals(
                "not-a-commit-hash\n",
                Files.readString(branchRef, StandardCharsets.UTF_8),
                "integrity check must not modify repository metadata"
        );
    }

    @Test
    void failsClearlyForUninitializedRepository() {
        IntegrityChecker checker = new IntegrityChecker(
                new Repository(temporaryDirectory)
        );

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                checker::check
        );
        assertTrue(error.getMessage().contains("not initialized"));
    }

    private TestRepository committedFile() throws IOException {
        TestRepository repo = new TestRepository(temporaryDirectory);
        repo.write("hello.txt", "intact content");
        repo.commit("add file", "hello.txt");
        return repo;
    }

    private String blobHash(TestRepository repo) throws IOException {
        CommitStore.CommitData head = new CommitStore(
                repo.repository.getMetadataDirectory()
        ).readCommit(new BranchManager(repo.repository).getCurrentCommit());
        return head.files().get("hello.txt");
    }

    private Path objectPath(TestRepository repo, String hash) {
        return repo.repository.getMetadataDirectory()
                .resolve("objects")
                .resolve(hash);
    }
}

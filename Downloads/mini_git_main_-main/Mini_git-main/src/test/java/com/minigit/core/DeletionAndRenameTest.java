package com.minigit.core;

import com.minigit.command.CommitCommand;
import com.minigit.storage.FileObjectStore;
import com.minigit.util.HashUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DeletionAndRenameTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void deletedTrackedFileIsReportedAsDeletedNotUntracked()
            throws IOException {
        TestRepository repo = new TestRepository(temporaryDirectory);
        repo.write("tracked.txt", "tracked content");
        repo.commit("add tracked file", "tracked.txt");
        Files.delete(temporaryDirectory.resolve("tracked.txt"));

        var files = new WorkingTreeStatus(repo.repository).getFiles();

        assertEquals(1, files.size());
        assertEquals("tracked.txt", files.get(0).path());
        assertEquals("DELETED", files.get(0).status());
        assertEquals("DELETED", files.get(0).changeType());
    }

    @Test
    void stagesAndCommitsDeletionThenReportsCleanTree()
            throws IOException {
        TestRepository repo = new TestRepository(temporaryDirectory);
        repo.write("tracked.txt", "tracked content");
        repo.commit("add tracked file", "tracked.txt");
        Files.delete(temporaryDirectory.resolve("tracked.txt"));

        repo.stage("tracked.txt");
        var staged = new WorkingTreeStatus(repo.repository).getFiles();
        assertEquals(1, staged.size());
        assertEquals("STAGED", staged.get(0).status());
        assertEquals("DELETED", staged.get(0).changeType());
        assertTrue(Index.isDeletion(
                new Index(repo.repository.getMetadataDirectory())
                        .getEntries().get("tracked.txt")
        ));

        new CommitCommand(repo.repository).execute("delete tracked file");

        String head = new BranchManager(repo.repository).getCurrentCommit();
        assertFalse(new CommitStore(
                repo.repository.getMetadataDirectory()
        ).readSnapshot(head).containsKey("tracked.txt"));
        assertTrue(new WorkingTreeStatus(repo.repository).getFiles().isEmpty());
        assertTrue(new Index(
                repo.repository.getMetadataDirectory()
        ).getEntries().isEmpty());
    }

    @Test
    void identifiesIdenticalContentMoveAsRename() throws IOException {
        TestRepository repo = new TestRepository(temporaryDirectory);
        repo.write("before.txt", "same bytes");
        repo.commit("add old path", "before.txt");
        Files.move(
                temporaryDirectory.resolve("before.txt"),
                temporaryDirectory.resolve("after.txt")
        );

        var files = new WorkingTreeStatus(repo.repository).getFiles();
        var renamed = files.stream()
                .filter(file -> "RENAMED".equals(file.changeType()))
                .findFirst()
                .orElseThrow();

        assertEquals("after.txt", renamed.path());
        assertEquals("before.txt", renamed.renameFrom());

        repo.stage("before.txt");
        repo.stage("after.txt");
        new CommitCommand(repo.repository).execute("rename file");

        String head = new BranchManager(repo.repository).getCurrentCommit();
        Map<String, String> snapshot = new CommitStore(
                repo.repository.getMetadataDirectory()
        ).readSnapshot(head);
        assertFalse(snapshot.containsKey("before.txt"));
        assertEquals(
                HashUtil.sha256("same bytes"),
                snapshot.get("after.txt")
        );
    }

    @Test
    void unrelatedAdditionAndDeletionAreNotReportedAsRename()
            throws IOException {
        TestRepository repo = new TestRepository(temporaryDirectory);
        repo.write("old.txt", "old content");
        repo.commit("add old path", "old.txt");
        Files.delete(temporaryDirectory.resolve("old.txt"));
        repo.write("new.txt", "different content");

        var files = new WorkingTreeStatus(repo.repository).getFiles();

        assertTrue(files.stream().anyMatch(
                file -> file.path().equals("old.txt")
                        && "DELETED".equals(file.status())
        ));
        assertTrue(files.stream().anyMatch(
                file -> file.path().equals("new.txt")
                        && "UNTRACKED".equals(file.status())
        ));
        assertTrue(files.stream().noneMatch(
                file -> "RENAMED".equals(file.changeType())
        ));
    }

    @Test
    void ambiguousHashMatchesRemainDeletionAndAddition() {
        String sharedHash = HashUtil.sha256("same");

        assertTrue(RenameDetector.detect(
                Map.of("old.txt", sharedHash),
                Map.of(
                        "new-a.txt", sharedHash,
                        "new-b.txt", sharedHash
                )
        ).isEmpty());

        assertTrue(RenameDetector.detect(
                Map.of(
                        "old-a.txt", sharedHash,
                        "old-b.txt", sharedHash
                ),
                Map.of("new.txt", sharedHash)
        ).isEmpty());
    }

    @Test
    void undoingDeletionRestagesTheDeletion() throws IOException {
        TestRepository repo = new TestRepository(temporaryDirectory);
        repo.write("tracked.txt", "tracked content");
        repo.commit("add tracked file", "tracked.txt");
        Files.delete(temporaryDirectory.resolve("tracked.txt"));
        repo.stage("tracked.txt");
        repo.commit("delete tracked file", "tracked.txt");

        UndoManager.UndoResult undo =
                new UndoManager(repo.repository).undoLastCommit();

        assertTrue(undo.restagedFiles().contains("tracked.txt"));
        assertTrue(Index.isDeletion(
                new Index(repo.repository.getMetadataDirectory())
                        .getEntries().get("tracked.txt")
        ));
        var status = new WorkingTreeStatus(repo.repository).getFiles();
        assertEquals("STAGED", status.get(0).status());
        assertEquals("DELETED", status.get(0).changeType());
    }

    @Test
    void readsLegacyV1CommitsAndBuildsNewSnapshotsOnTop()
            throws IOException {
        TestRepository repo = new TestRepository(temporaryDirectory);
        FileObjectStore objectStore = new FileObjectStore(
                repo.repository.getMetadataDirectory()
        );
        String blobHash = objectStore.store(
                "legacy content".getBytes(StandardCharsets.UTF_8)
        );
        String encodedPath = Base64.getEncoder().encodeToString(
                "legacy.txt".getBytes(StandardCharsets.UTF_8)
        );
        String legacyObject = "MINIGIT_COMMIT_V1\n"
                + "parent=\n"
                + "timestamp=" + Instant.now() + "\n"
                + "message=" + Base64.getEncoder().encodeToString(
                        "legacy".getBytes(StandardCharsets.UTF_8)
                ) + "\n"
                + "files=1\n"
                + encodedPath + "\t" + blobHash + "\n";
        String legacyHash = objectStore.store(
                legacyObject.getBytes(StandardCharsets.UTF_8)
        );
        Files.writeString(
                repo.repository.getMetadataDirectory()
                        .resolve("refs")
                        .resolve("heads")
                        .resolve("main"),
                legacyHash + "\n",
                StandardCharsets.UTF_8
        );

        assertEquals(
                blobHash,
                new CommitStore(repo.repository.getMetadataDirectory())
                        .readSnapshot(legacyHash).get("legacy.txt")
        );

        repo.write("new.txt", "new content");
        String nextHash = repo.commit("add V2 file", "new.txt");
        Map<String, String> nextSnapshot = new CommitStore(
                repo.repository.getMetadataDirectory()
        ).readSnapshot(nextHash);

        assertEquals(blobHash, nextSnapshot.get("legacy.txt"));
        assertEquals(
                HashUtil.sha256("new content"),
                nextSnapshot.get("new.txt")
        );
        assertTrue(new CommitStore(
                repo.repository.getMetadataDirectory()
        ).readCommit(nextHash).completeSnapshot());
    }
}

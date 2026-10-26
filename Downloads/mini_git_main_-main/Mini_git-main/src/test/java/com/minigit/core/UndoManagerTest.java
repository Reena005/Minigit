package com.minigit.core;

import com.minigit.exception.NothingToUndoException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

class UndoManagerTest {

    @TempDir
    java.nio.file.Path temporaryDirectory;

    @Test
    void undoMovesBranchBackAndRestagesChangedFiles() throws IOException {
        TestRepository repo = new TestRepository(temporaryDirectory);
        repo.write("a.txt", "one");
        repo.write("b.txt", "stable");
        String first = repo.commit("first", "a.txt", "b.txt");

        repo.write("a.txt", "two");
        String second = repo.commit("second", "a.txt");

        UndoManager.UndoResult result =
                new UndoManager(repo.repository).undoLastCommit();

        BranchManager branches = new BranchManager(repo.repository);

        assertEquals(second, result.undoneCommit());
        assertEquals("second", result.undoneMessage());
        assertEquals(first, result.newHead());
        assertEquals(first, branches.getCurrentCommit());

        // Only the file changed by the undone commit returns to staging.
        assertEquals(java.util.List.of("a.txt"), result.restagedFiles());
        assertEquals(1, new StagingManager(repo.repository)
                .getStagedFiles().size());

        // The working directory is never modified by undo.
        assertEquals("two", repo.read("a.txt"));
        assertEquals("stable", repo.read("b.txt"));
    }

    @Test
    void undoingTheRootCommitLeavesAnEmptyBranch() throws IOException {
        TestRepository repo = new TestRepository(temporaryDirectory);
        repo.write("a.txt", "one");
        repo.write("b.txt", "two");
        repo.commit("first", "a.txt", "b.txt");

        UndoManager.UndoResult result =
                new UndoManager(repo.repository).undoLastCommit();

        BranchManager branches = new BranchManager(repo.repository);

        assertEquals("", result.newHead());
        assertEquals("", branches.getCurrentCommit());
        assertEquals(2, result.restagedFiles().size());
        assertEquals("main", branches.getCurrentBranch());
        assertTrue(repo.exists("a.txt"));
    }

    @Test
    void undoRefusesWhileFilesAreStaged() throws IOException {
        TestRepository repo = new TestRepository(temporaryDirectory);
        repo.write("a.txt", "one");
        String first = repo.commit("first", "a.txt");

        repo.write("b.txt", "pending");
        repo.stage("b.txt");

        assertThrows(
                IllegalStateException.class,
                () -> new UndoManager(repo.repository).undoLastCommit()
        );
        assertEquals(
                first,
                new BranchManager(repo.repository).getCurrentCommit()
        );
    }

    @Test
    void undoWithoutCommitsFails() throws IOException {
        TestRepository repo = new TestRepository(temporaryDirectory);

        assertThrows(
                NothingToUndoException.class,
                () -> new UndoManager(repo.repository).undoLastCommit()
        );
    }

    @Test
    void canCommitAgainAfterUndo() throws IOException {
        TestRepository repo = new TestRepository(temporaryDirectory);
        repo.write("a.txt", "one");
        String first = repo.commit("first", "a.txt");

        repo.write("a.txt", "two");
        repo.commit("second", "a.txt");

        new UndoManager(repo.repository).undoLastCommit();

        // The changes are staged again, so a fresh commit works.
        new com.minigit.command.CommitCommand(repo.repository)
                .execute("second, redone");

        CommitStore store = new CommitStore(
                repo.repository.getMetadataDirectory()
        );
        String tip = new BranchManager(repo.repository).getCurrentCommit();

        assertEquals("second, redone", store.readCommit(tip).message());
        assertEquals(first, store.readCommit(tip).parent());
    }

    @Test
    void undoneCommitCanBeRecoveredFromTheReflog() throws IOException {
        TestRepository repo = new TestRepository(temporaryDirectory);
        repo.write("a.txt", "one");
        repo.commit("first", "a.txt");
        repo.write("a.txt", "two");
        String second = repo.commit("second", "a.txt");

        new UndoManager(repo.repository).undoLastCommit();

        String loggedHash = Reflog.read(
                repo.repository.getMetadataDirectory(), 1
        ).get(0).oldHash();

        assertEquals(second, loggedHash);

        BranchManager branches = new BranchManager(repo.repository);
        branches.createBranch("rescued", loggedHash);

        assertEquals(second, branches.listBranches().stream()
                .filter(b -> b.name().equals("rescued"))
                .findFirst().orElseThrow().commit());
    }
}

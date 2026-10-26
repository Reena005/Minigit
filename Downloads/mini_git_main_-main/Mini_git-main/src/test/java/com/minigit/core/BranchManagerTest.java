package com.minigit.core;

import com.minigit.exception.BranchNotFoundException;
import com.minigit.exception.CheckoutConflictException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BranchManagerTest {

    @TempDir
    java.nio.file.Path temporaryDirectory;

    @Test
    void listsCurrentBranchBeforeFirstCommit() throws IOException {
        TestRepository repo = new TestRepository(temporaryDirectory);
        BranchManager branches = new BranchManager(repo.repository);

        List<BranchManager.BranchInfo> list = branches.listBranches();

        assertEquals(1, list.size());
        assertEquals("main", list.get(0).name());
        assertEquals("", list.get(0).commit());
        assertTrue(list.get(0).current());
    }

    @Test
    void cannotCreateBranchBeforeFirstCommit() throws IOException {
        TestRepository repo = new TestRepository(temporaryDirectory);
        BranchManager branches = new BranchManager(repo.repository);

        assertThrows(
                IllegalStateException.class,
                () -> branches.createBranch("feature", null)
        );
    }

    @Test
    void createsBranchAtCurrentCommit() throws IOException {
        TestRepository repo = new TestRepository(temporaryDirectory);
        repo.write("a.txt", "one");
        String first = repo.commit("first", "a.txt");

        BranchManager branches = new BranchManager(repo.repository);
        BranchManager.BranchInfo created =
                branches.createBranch("feature", null);

        assertEquals(first, created.commit());
        assertEquals(2, branches.listBranches().size());
        assertEquals("main", branches.getCurrentBranch());
    }

    @Test
    void rejectsInvalidAndDuplicateNames() throws IOException {
        TestRepository repo = new TestRepository(temporaryDirectory);
        repo.write("a.txt", "one");
        repo.commit("first", "a.txt");
        BranchManager branches = new BranchManager(repo.repository);

        for (String bad : new String[]{
                "", " ", "../evil", "a/b", "-x", "x..y", "x.lock",
                "name with space", "x.", "x.tmp"}) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> branches.createBranch(bad, null),
                    "should reject '" + bad + "'"
            );
        }

        branches.createBranch("feature", null);

        assertThrows(
                IllegalArgumentException.class,
                () -> branches.createBranch("feature", null)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> branches.createBranch("main", null)
        );
    }

    @Test
    void checkoutSwitchesFilesBetweenBranches() throws IOException {
        TestRepository repo = new TestRepository(temporaryDirectory);
        BranchManager branches = new BranchManager(repo.repository);

        repo.write("a.txt", "one");
        repo.commit("first", "a.txt");

        branches.createAndCheckout("feature");
        assertEquals("feature", branches.getCurrentBranch());

        repo.write("a.txt", "two");
        repo.write("docs/b.txt", "extra");
        repo.commit("feature work", "a.txt", "docs/b.txt");

        BranchManager.CheckoutResult toMain = branches.checkout("main");

        assertEquals("main", branches.getCurrentBranch());
        assertEquals("one", repo.read("a.txt"));
        assertFalse(repo.exists("docs/b.txt"));
        assertFalse(repo.exists("docs"), "empty folder should be removed");
        assertEquals(1, toMain.filesWritten());
        assertEquals(1, toMain.filesRemoved());

        branches.checkout("feature");

        assertEquals("two", repo.read("a.txt"));
        assertEquals("extra", repo.read("docs/b.txt"));
    }

    @Test
    void commitsGoToTheCurrentBranchOnly() throws IOException {
        TestRepository repo = new TestRepository(temporaryDirectory);
        BranchManager branches = new BranchManager(repo.repository);

        repo.write("a.txt", "one");
        String mainTip = repo.commit("first", "a.txt");

        branches.createAndCheckout("feature");
        repo.write("a.txt", "two");
        String featureTip = repo.commit("second", "a.txt");

        assertFalse(mainTip.equals(featureTip));

        for (BranchManager.BranchInfo info : branches.listBranches()) {
            if (info.name().equals("main")) {
                assertEquals(mainTip, info.commit());
            } else {
                assertEquals(featureTip, info.commit());
            }
        }
    }

    @Test
    void checkoutRefusesToOverwriteLocalChanges() throws IOException {
        TestRepository repo = new TestRepository(temporaryDirectory);
        BranchManager branches = new BranchManager(repo.repository);

        repo.write("a.txt", "one");
        repo.commit("first", "a.txt");
        branches.createAndCheckout("feature");
        repo.write("a.txt", "two");
        repo.commit("second", "a.txt");

        // Uncommitted edit to a file that differs between the branches.
        repo.write("a.txt", "my unsaved work");

        CheckoutConflictException e = assertThrows(
                CheckoutConflictException.class,
                () -> branches.checkout("main")
        );

        assertTrue(e.getConflicts().get(0).startsWith("a.txt"));
        assertEquals("feature", branches.getCurrentBranch());
        assertEquals("my unsaved work", repo.read("a.txt"));
    }

    @Test
    void checkoutRefusesWhenFilesAreStaged() throws IOException {
        TestRepository repo = new TestRepository(temporaryDirectory);
        BranchManager branches = new BranchManager(repo.repository);

        repo.write("a.txt", "one");
        repo.commit("first", "a.txt");
        branches.createAndCheckout("feature");
        repo.write("a.txt", "two");
        repo.commit("second", "a.txt");

        repo.write("new.txt", "staged");
        repo.stage("new.txt");

        assertThrows(
                CheckoutConflictException.class,
                () -> branches.checkout("main")
        );
        assertEquals("feature", branches.getCurrentBranch());
        assertEquals(1, new Index(
                repo.repository.getMetadataDirectory()
        ).getEntries().size());
    }

    @Test
    void checkoutRefusesToOverwriteUntrackedFile() throws IOException {
        TestRepository repo = new TestRepository(temporaryDirectory);
        BranchManager branches = new BranchManager(repo.repository);

        repo.write("a.txt", "one");
        repo.commit("first", "a.txt");
        branches.createAndCheckout("feature");
        repo.write("extra.txt", "from feature");
        repo.commit("add extra", "extra.txt");
        branches.checkout("main"); // extra.txt removed

        repo.write("extra.txt", "local untracked");

        assertThrows(
                CheckoutConflictException.class,
                () -> branches.checkout("feature")
        );
        assertEquals("local untracked", repo.read("extra.txt"));
        assertEquals("main", branches.getCurrentBranch());
    }

    @Test
    void checkoutKeepsLocalChangesToSharedFiles() throws IOException {
        TestRepository repo = new TestRepository(temporaryDirectory);
        BranchManager branches = new BranchManager(repo.repository);

        repo.write("shared.txt", "same on both");
        repo.write("a.txt", "one");
        repo.commit("first", "shared.txt", "a.txt");
        branches.createAndCheckout("feature");
        repo.write("a.txt", "two");
        repo.commit("second", "a.txt");

        repo.write("shared.txt", "edited locally");
        branches.checkout("main");

        assertEquals("one", repo.read("a.txt"));
        assertEquals("edited locally", repo.read("shared.txt"));
    }

    @Test
    void switchingBetweenBranchesAtTheSameCommitKeepsStagedFiles()
            throws IOException {
        TestRepository repo = new TestRepository(temporaryDirectory);
        BranchManager branches = new BranchManager(repo.repository);

        repo.write("a.txt", "one");
        repo.commit("first", "a.txt");

        repo.write("b.txt", "new");
        repo.stage("b.txt");

        branches.createAndCheckout("feature");

        assertEquals("feature", branches.getCurrentBranch());
        assertEquals(1, new Index(
                repo.repository.getMetadataDirectory()
        ).getEntries().size());
    }

    @Test
    void checkoutOfUnknownBranchFails() throws IOException {
        TestRepository repo = new TestRepository(temporaryDirectory);
        repo.write("a.txt", "one");
        repo.commit("first", "a.txt");
        BranchManager branches = new BranchManager(repo.repository);

        assertThrows(
                BranchNotFoundException.class,
                () -> branches.checkout("nope")
        );
    }

    @Test
    void checkoutOfCurrentBranchIsANoOp() throws IOException {
        TestRepository repo = new TestRepository(temporaryDirectory);
        repo.write("a.txt", "one");
        repo.commit("first", "a.txt");
        BranchManager branches = new BranchManager(repo.repository);

        assertTrue(branches.checkout("main").alreadyOnBranch());
    }

    @Test
    void deleteProtectsCurrentBranchAndUnmergedWork() throws IOException {
        TestRepository repo = new TestRepository(temporaryDirectory);
        BranchManager branches = new BranchManager(repo.repository);

        repo.write("a.txt", "one");
        repo.commit("first", "a.txt");

        branches.createBranch("merged", null);
        branches.createAndCheckout("feature");
        repo.write("a.txt", "two");
        String unmergedTip = repo.commit("second", "a.txt");
        branches.checkout("main");

        assertThrows(
                IllegalStateException.class,
                () -> branches.deleteBranch("main", true)
        );
        assertThrows(
                IllegalStateException.class,
                () -> branches.deleteBranch("feature", false)
        );
        assertThrows(
                BranchNotFoundException.class,
                () -> branches.deleteBranch("ghost", false)
        );

        branches.deleteBranch("merged", false);
        assertFalse(branches.branchExists("merged"));

        branches.deleteBranch("feature", true);
        assertFalse(branches.branchExists("feature"));

        // The deleted tip is recoverable through the reflog.
        boolean found = Reflog.read(
                repo.repository.getMetadataDirectory(), 0
        ).stream().anyMatch(entry ->
                entry.action().equals("branch-delete")
                        && entry.oldHash().equals(unmergedTip));
        assertTrue(found);

        BranchManager.BranchInfo recovered =
                branches.createBranch("recovered", unmergedTip);
        assertEquals(unmergedTip, recovered.commit());
    }

    @Test
    void createBranchRejectsUnknownStartPoint() throws IOException {
        TestRepository repo = new TestRepository(temporaryDirectory);
        repo.write("a.txt", "one");
        repo.commit("first", "a.txt");
        BranchManager branches = new BranchManager(repo.repository);

        assertThrows(
                IllegalArgumentException.class,
                () -> branches.createBranch("x", "0".repeat(64))
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> branches.createBranch("x", "not-a-hash")
        );
    }
}

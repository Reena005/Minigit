package com.minigit.command;

import com.minigit.core.BranchManager;
import com.minigit.core.Repository;

import java.io.IOException;
import java.util.List;

/** CLI: list, create and delete branches. */
public class BranchCommand {

    private final BranchManager branches;

    public BranchCommand(Repository repository) {
        this.branches = new BranchManager(repository);
    }

    public void list() throws IOException {

        List<BranchManager.BranchInfo> all = branches.listBranches();

        for (BranchManager.BranchInfo branch : all) {

            String shortHash = branch.commit().isEmpty()
                    ? "(no commits yet)"
                    : branch.commit().substring(0, 12);

            System.out.println(
                    (branch.current() ? "* " : "  ")
                            + branch.name() + "  " + shortHash
            );
        }
    }

    public void create(String name, String startPoint) throws IOException {

        BranchManager.BranchInfo created =
                branches.createBranch(name, startPoint);

        System.out.println(
                "Created branch '" + created.name() + "' at "
                        + created.commit().substring(0, 12)
        );
    }

    public void delete(String name, boolean force) throws IOException {

        branches.deleteBranch(name, force);

        System.out.println("Deleted branch '" + name + "'.");
    }
}

package com.minigit.command;

import com.minigit.core.BranchManager;
import com.minigit.core.Repository;

import java.io.IOException;

/** CLI: switch branches, optionally creating the branch first. */
public class CheckoutCommand {

    private final BranchManager branches;

    public CheckoutCommand(Repository repository) {
        this.branches = new BranchManager(repository);
    }

    public void execute(String branchName, boolean create)
            throws IOException {

        BranchManager.CheckoutResult result = create
                ? branches.createAndCheckout(branchName)
                : branches.checkout(branchName);

        if (result.alreadyOnBranch()) {
            System.out.println(
                    "Already on branch '" + result.branch() + "'."
            );
            return;
        }

        System.out.println(
                "Switched to branch '" + result.branch() + "'."
        );
        System.out.println("Files written: " + result.filesWritten());
        System.out.println("Files removed: " + result.filesRemoved());
    }
}

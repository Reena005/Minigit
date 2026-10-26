package com.minigit.exception;

/**
 * Thrown when a branch name does not refer to an existing branch.
 */
public class BranchNotFoundException extends IllegalArgumentException {

    private static final long serialVersionUID = 1L;

    private final String branchName;

    public BranchNotFoundException(String branchName) {
        super("Branch not found: " + branchName);
        this.branchName = branchName;
    }

    public String getBranchName() {
        return branchName;
    }
}

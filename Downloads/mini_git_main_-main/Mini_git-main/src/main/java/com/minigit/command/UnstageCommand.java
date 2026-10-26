package com.minigit.command;

import com.minigit.core.Repository;
import com.minigit.core.StagingManager;

import java.io.IOException;

/** CLI: remove files from the staging area without touching their content. */
public class UnstageCommand {

    private final StagingManager staging;

    public UnstageCommand(Repository repository) {
        this.staging = new StagingManager(repository);
    }

    public void execute(String path) throws IOException {
        String unstaged = staging.unstage(path);
        System.out.println("Unstaged: " + unstaged);
    }

    public void executeAll() throws IOException {
        int count = staging.unstageAll();
        System.out.println("Unstaged " + count + " file(s).");
    }
}

package com.minigit.command;

import com.minigit.core.Repository;
import com.minigit.core.WorkingTreeStatus;

import java.io.IOException;

public class StatusCommand {

    private final Repository repository;

    public StatusCommand(Repository repository) {
        this.repository = repository;
    }

    public void execute() throws IOException {

        var files = new WorkingTreeStatus(repository).getFiles();

        for (WorkingTreeStatus.FileStatus file : files) {
            if ("RENAMED".equals(file.changeType())
                    && file.renameFrom() != null) {
                System.out.println(
                        "Renamed: " + file.renameFrom() + " -> " + file.path()
                );
            } else if ("STAGED".equals(file.status())) {
                System.out.println(
                        "Staged " + file.changeType() + ": " + file.path()
                );
            } else {
                System.out.println(
                        file.status() + ": " + file.path()
                );
            }
        }

        if (files.isEmpty()) {
            System.out.println("Working tree clean.");
        }
    }
}
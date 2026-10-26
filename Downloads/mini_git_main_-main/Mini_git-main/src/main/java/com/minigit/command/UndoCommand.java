package com.minigit.command;

import com.minigit.core.Repository;
import com.minigit.core.UndoManager;

import java.io.IOException;

/** CLI: undo the last commit (soft reset). */
public class UndoCommand {

    private final UndoManager undo;

    public UndoCommand(Repository repository) {
        this.undo = new UndoManager(repository);
    }

    public void execute() throws IOException {

        UndoManager.UndoResult result = undo.undoLastCommit();

        System.out.println(
                "Undid commit " + result.undoneCommit().substring(0, 12)
                        + ": " + result.undoneMessage()
        );
        System.out.println("Branch: " + result.branch());
        System.out.println(
                "Files back in the staging area: "
                        + result.restagedFiles().size()
        );

        for (String path : result.restagedFiles()) {
            System.out.println("  staged: " + path);
        }

        System.out.println(
                "Recover this commit any time with: branch <name> "
                        + result.undoneCommit()
        );
    }
}

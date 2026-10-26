package com.minigit.api;

import com.minigit.core.Repository;
import com.minigit.core.RepositoryManager;
import com.minigit.core.UndoManager;

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

/**
 * Module 10 - undo the last commit (soft reset).
 *
 * POST /api/undo
 */
@WebServlet("/api/undo")
public class UndoServlet extends HttpServlet {

    private static final String METHODS = "POST, OPTIONS";

    @Override
    protected void doOptions(
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        ApiSupport.preflight(response, METHODS);
    }

    @Override
    protected void doPost(
            HttpServletRequest request,
            HttpServletResponse response
    ) throws IOException {

        ApiSupport.handle(response, METHODS, () -> {

            UndoManager.UndoResult result = new UndoManager(
                    new Repository(RepositoryManager.getRepositoryPath())
            ).undoLastCommit();

            return "{\"success\":true"
                    + ",\"message\":" + ApiSupport.quote(
                    "Undid commit: " + result.undoneMessage())
                    + ",\"branch\":" + ApiSupport.quote(result.branch())
                    + ",\"undoneCommit\":"
                    + ApiSupport.quote(result.undoneCommit())
                    + ",\"undoneMessage\":"
                    + ApiSupport.quote(result.undoneMessage())
                    + ",\"newHead\":" + ApiSupport.quote(result.newHead())
                    + ",\"restagedFiles\":"
                    + ApiSupport.stringArray(result.restagedFiles())
                    + "}";
        });
    }
}

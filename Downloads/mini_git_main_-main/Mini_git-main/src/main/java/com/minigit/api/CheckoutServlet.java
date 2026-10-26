package com.minigit.api;

import com.minigit.core.BranchManager;
import com.minigit.core.Repository;
import com.minigit.core.RepositoryManager;

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

/**
 * Module 9 - switch branches.
 *
 * POST /api/checkout  branch=<name>[&create=true]
 */
@WebServlet("/api/checkout")
public class CheckoutServlet extends HttpServlet {

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

        String branch = request.getParameter("branch");

        if (branch == null) {
            branch = request.getParameter("name");
        }

        String target = branch;
        boolean create =
                ApiSupport.isTrue(request.getParameter("create"));

        ApiSupport.handle(response, METHODS, () -> {

            BranchManager manager = new BranchManager(
                    new Repository(RepositoryManager.getRepositoryPath())
            );

            BranchManager.CheckoutResult result = create
                    ? manager.createAndCheckout(target)
                    : manager.checkout(target);

            String message = result.alreadyOnBranch()
                    ? "Already on branch '" + result.branch() + "'"
                    : "Switched to branch '" + result.branch() + "'";

            return "{\"success\":true"
                    + ",\"message\":" + ApiSupport.quote(message)
                    + ",\"branch\":" + ApiSupport.quote(result.branch())
                    + ",\"previousBranch\":"
                    + ApiSupport.quote(result.previousBranch())
                    + ",\"commit\":" + ApiSupport.quote(result.commit())
                    + ",\"filesWritten\":" + result.filesWritten()
                    + ",\"filesRemoved\":" + result.filesRemoved()
                    + ",\"alreadyOnBranch\":" + result.alreadyOnBranch()
                    + "}";
        });
    }
}

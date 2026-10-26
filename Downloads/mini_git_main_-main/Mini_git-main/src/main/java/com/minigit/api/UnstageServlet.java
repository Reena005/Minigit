package com.minigit.api;

import com.minigit.core.Repository;
import com.minigit.core.RepositoryManager;
import com.minigit.core.StagingManager;

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

/**
 * Module 10 - take files out of the staging area.
 *
 * POST /api/unstage  path=<file>   unstage one file
 * POST /api/unstage  all=true      unstage everything
 *
 * Only the index changes; working-directory files are never modified.
 */
@WebServlet("/api/unstage")
public class UnstageServlet extends HttpServlet {

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

        String path = request.getParameter("path");
        boolean all = ApiSupport.isTrue(request.getParameter("all"));

        ApiSupport.handle(response, METHODS, () -> {

            StagingManager staging = new StagingManager(
                    new Repository(RepositoryManager.getRepositoryPath())
            );

            if (all) {
                int count = staging.unstageAll();

                return "{\"success\":true,\"message\":"
                        + ApiSupport.quote(
                        "Unstaged " + count + " file(s)")
                        + ",\"count\":" + count + "}";
            }

            String unstaged = staging.unstage(path);

            return "{\"success\":true,\"message\":"
                    + ApiSupport.quote("Unstaged " + unstaged)
                    + ",\"path\":" + ApiSupport.quote(unstaged)
                    + ",\"count\":1}";
        });
    }
}

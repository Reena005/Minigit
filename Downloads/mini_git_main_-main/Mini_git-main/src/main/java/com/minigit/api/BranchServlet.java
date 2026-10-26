package com.minigit.api;

import com.minigit.core.BranchManager;
import com.minigit.core.Repository;
import com.minigit.core.RepositoryManager;

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.List;

/**
 * Module 9 - branch management API.
 *
 * GET    /api/branches                      list branches
 * POST   /api/branches  name[,from][,checkout=true]   create a branch
 * DELETE /api/branches?name=...[&force=true]          delete a branch
 */
@WebServlet("/api/branches")
public class BranchServlet extends HttpServlet {

    private static final String METHODS = "GET, POST, DELETE, OPTIONS";

    private BranchManager manager() {
        return new BranchManager(
                new Repository(RepositoryManager.getRepositoryPath())
        );
    }

    @Override
    protected void doOptions(
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        ApiSupport.preflight(response, METHODS);
    }

    @Override
    protected void doGet(
            HttpServletRequest request,
            HttpServletResponse response
    ) throws IOException {

        ApiSupport.handle(response, METHODS, () -> {

            BranchManager manager = manager();
            List<BranchManager.BranchInfo> branches =
                    manager.listBranches();

            StringBuilder json = new StringBuilder();

            json.append("{\"success\":true,\"currentBranch\":")
                    .append(ApiSupport.quote(manager.getCurrentBranch()))
                    .append(",\"branches\":[");

            for (int i = 0; i < branches.size(); i++) {

                BranchManager.BranchInfo branch = branches.get(i);

                if (i > 0) {
                    json.append(',');
                }

                json.append("{\"name\":")
                        .append(ApiSupport.quote(branch.name()))
                        .append(",\"commit\":")
                        .append(ApiSupport.quote(branch.commit()))
                        .append(",\"current\":")
                        .append(branch.current())
                        .append('}');
            }

            return json.append("]}").toString();
        });
    }

    @Override
    protected void doPost(
            HttpServletRequest request,
            HttpServletResponse response
    ) throws IOException {

        String name = request.getParameter("name");
        String from = request.getParameter("from");
        boolean checkout =
                ApiSupport.isTrue(request.getParameter("checkout"));

        ApiSupport.handle(response, METHODS, () -> {

            BranchManager manager = manager();

            if (checkout) {
                BranchManager.CheckoutResult result =
                        manager.createAndCheckout(name, from);

                return "{\"success\":true,"
                        + "\"message\":" + ApiSupport.quote(
                        "Created and switched to branch '"
                                + result.branch() + "'")
                        + ",\"branch\":" + ApiSupport.quote(result.branch())
                        + ",\"commit\":" + ApiSupport.quote(result.commit())
                        + ",\"checkedOut\":true}";
            }

            BranchManager.BranchInfo created =
                    manager.createBranch(name, from);

            return "{\"success\":true,"
                    + "\"message\":" + ApiSupport.quote(
                    "Created branch '" + created.name() + "'")
                    + ",\"branch\":" + ApiSupport.quote(created.name())
                    + ",\"commit\":" + ApiSupport.quote(created.commit())
                    + ",\"checkedOut\":false}";
        });
    }

    @Override
    protected void doDelete(
            HttpServletRequest request,
            HttpServletResponse response
    ) throws IOException {

        String name = request.getParameter("name");
        boolean force = ApiSupport.isTrue(request.getParameter("force"));

        ApiSupport.handle(response, METHODS, () -> {

            manager().deleteBranch(name, force);

            return "{\"success\":true,\"message\":"
                    + ApiSupport.quote("Deleted branch '" + name + "'")
                    + "}";
        });
    }
}

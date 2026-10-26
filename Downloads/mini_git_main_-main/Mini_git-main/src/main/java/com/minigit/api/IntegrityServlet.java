package com.minigit.api;

import com.minigit.core.IntegrityChecker;
import com.minigit.core.Repository;
import com.minigit.core.RepositoryManager;

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

@WebServlet("/api/integrity")
public class IntegrityServlet extends HttpServlet {

    @Override
    protected void doOptions(
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        setCors(response);
        response.setStatus(HttpServletResponse.SC_NO_CONTENT);
    }

    @Override
    protected void doGet(
            HttpServletRequest request,
            HttpServletResponse response
    ) throws IOException {
        setCors(response);

        Repository repository =
                new Repository(RepositoryManager.getRepositoryPath());

        try {
            IntegrityChecker.IntegrityReport report =
                    new IntegrityChecker(repository).check();
            response.setStatus(HttpServletResponse.SC_OK);
            response.getWriter().write(toJson(report));
        } catch (IllegalStateException e) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            response.getWriter().write(
                    "{\"success\":false,\"totalObjectsChecked\":0,"
                            + "\"validObjectCount\":0,"
                            + "\"corruptedObjectCount\":0,"
                            + "\"missingObjectCount\":0,"
                            + "\"unreadableObjectCount\":0,"
                            + "\"status\":\"UNAVAILABLE\","
                            + "\"message\":\"" + escapeJson(e.getMessage())
                            + "\",\"results\":[]}"
            );
        } catch (IOException e) {
            getServletContext().log(
                    "MiniGit integrity check failed.",
                    e
            );
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            response.getWriter().write(
                    "{\"success\":false,\"totalObjectsChecked\":0,"
                            + "\"validObjectCount\":0,"
                            + "\"corruptedObjectCount\":0,"
                            + "\"missingObjectCount\":0,"
                            + "\"unreadableObjectCount\":0,"
                            + "\"status\":\"UNAVAILABLE\","
                            + "\"message\":\"" + escapeJson(e.getMessage())
                            + "\",\"results\":[]}"
            );
        }
    }

    private void setCors(HttpServletResponse response) {
        response.setHeader(
                "Access-Control-Allow-Origin",
                "http://localhost:5173"
        );
        response.setHeader(
                "Access-Control-Allow-Methods",
                "GET, OPTIONS"
        );
        response.setHeader(
                "Access-Control-Allow-Headers",
                "Content-Type"
        );
        response.setCharacterEncoding("UTF-8");
        response.setContentType("application/json");
    }

    private String toJson(IntegrityChecker.IntegrityReport report) {
        StringBuilder results = new StringBuilder("[");
        for (int index = 0; index < report.results().size(); index++) {
            if (index > 0) {
                results.append(',');
            }
            IntegrityChecker.FailedCheck result =
                    report.results().get(index);
            results.append("{\"objectHash\":\"")
                    .append(escapeJson(result.objectHash()))
                    .append("\",\"status\":\"")
                    .append(escapeJson(result.status()))
                    .append("\",\"reason\":\"")
                    .append(escapeJson(result.reason()))
                    .append("\"}");
        }
        results.append(']');

        return "{\"success\":" + report.success()
                + ",\"totalObjectsChecked\":" + report.totalObjectsChecked()
                + ",\"validObjectCount\":" + report.validObjectCount()
                + ",\"corruptedObjectCount\":"
                + report.corruptedObjectCount()
                + ",\"missingObjectCount\":"
                + report.missingObjectCount()
                + ",\"unreadableObjectCount\":"
                + report.unreadableObjectCount()
                + ",\"status\":\"" + escapeJson(report.status())
                + "\",\"results\":" + results + "}";
    }

    private String escapeJson(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}

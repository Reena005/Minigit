package com.minigit.api;

import com.minigit.core.Repository;
import com.minigit.core.RepositoryManager;
import com.minigit.core.WorkingTreeStatus;

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

@WebServlet("/api/status")
public class StatusServlet extends HttpServlet {

    @Override
    protected void doGet(
            HttpServletRequest request,
            HttpServletResponse response
    ) throws IOException {

        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());

        response.setHeader(
                "Access-Control-Allow-Origin",
                "http://localhost:5173"
        );

        Path repositoryPath = RepositoryManager.getRepositoryPath();
        Repository repository = new Repository(repositoryPath);

        if (!repository.isInitialized()) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);

            response.getWriter().write("""
                    {
                      "initialized": false,
                      "error": "MiniGit repository is not initialized"
                    }
                    """);

            return;
        }

        List<WorkingTreeStatus.FileStatus> statuses =
                new WorkingTreeStatus(repository).getFiles();
        List<String> filesJson = statuses.stream()
                .map(this::fileJson)
                .toList();
        String branch = new com.minigit.core.BranchManager(repository)
                .getCurrentBranch();

        String files = String.join(",", filesJson);

        String json = """
                {
                  "initialized": true,
                  "path": "%s",
                  "currentBranch": "%s",
                  "clean": %s,
                  "files": [%s]
                }
                """.formatted(
                escapeJson(repositoryPath.toString()),
                escapeJson(branch),
                filesJson.isEmpty(),
                files
        );

        response.getWriter().write(json);
    }

    private String fileJson(WorkingTreeStatus.FileStatus file) {
        return """
                {
                  "path": "%s",
                  "status": "%s",
                  "changeType": "%s",
                  "renameFrom": %s,
                  "staged": %s,
                  "workingChanged": %s
                }
                """.formatted(
                escapeJson(file.path()),
                escapeJson(file.status()),
                escapeJson(file.changeType()),
                file.renameFrom() == null
                        ? "null"
                        : "\"" + escapeJson(file.renameFrom()) + "\"",
                file.staged(),
                file.workingChanged()
        ).trim();
    }

    private String escapeJson(String value) {
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }
}
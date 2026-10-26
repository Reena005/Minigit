package com.minigit.api;

import com.minigit.core.Repository;
import com.minigit.core.RepositoryManager;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

@WebServlet("/api/repository")
public class RepositoryServlet extends HttpServlet {

    @Override
    protected void doGet(
            HttpServletRequest request,
            HttpServletResponse response
    ) throws IOException {

        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader("Access-Control-Allow-Origin", "http://localhost:5173");

        Path repositoryPath = RepositoryManager.getRepositoryPath();
        Repository repository = new Repository(repositoryPath);

        boolean initialized = repository.isInitialized();
        String branch = "unknown";

        if (initialized) {
            Path headFile = repository.getMetadataDirectory().resolve("HEAD");

            if (Files.isRegularFile(headFile)) {
                String head = Files.readString(headFile).trim();

                if (head.startsWith("ref: refs/heads/")) {
                    branch = head.substring("ref: refs/heads/".length());
                }
            }
        }

        String json = """
                {
                  "name": "MiniGit Repository",
                  "path": "%s",
                  "initialized": %s,
                  "currentBranch": "%s"
                }
                """.formatted(
                escapeJson(repositoryPath.toString()),
                initialized,
                escapeJson(branch)
        );

        response.getWriter().write(json);
    }

    private String escapeJson(String value) {
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }
}
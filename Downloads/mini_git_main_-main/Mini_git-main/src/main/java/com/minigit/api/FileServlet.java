package com.minigit.api;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Collectors;

import com.minigit.core.Repository;
import com.minigit.core.RepositoryManager;

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@WebServlet(urlPatterns = {"/api/files", "/api/file"})
public class FileServlet extends HttpServlet {

    private void configureResponse(HttpServletResponse response) {
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader(
                "Access-Control-Allow-Origin",
                "http://localhost:5173"
        );
    }

    @Override
    protected void doOptions(
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        configureResponse(response);
        response.setHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        response.setHeader("Access-Control-Allow-Headers", "Content-Type");
        response.setStatus(HttpServletResponse.SC_NO_CONTENT);
    }

    @Override
    protected void doGet(
            HttpServletRequest request,
            HttpServletResponse response
    ) throws IOException {

        configureResponse(response);

        Path repositoryPath = RepositoryManager.getRepositoryPath();
        Repository repository = new Repository(repositoryPath);

        if (!repository.isInitialized()) {
            sendError(response, 400, "MiniGit repository is not initialized.");
            return;
        }

        String servletPath = request.getServletPath();

        if ("/api/files".equals(servletPath)) {
            listFiles(repositoryPath, response);
            return;
        }

        String relativePath = request.getParameter("path");

        if (relativePath == null || relativePath.isBlank()) {
            sendError(response, 400, "Provide a file path.");
            return;
        }

        Path file = resolveSafeFile(repositoryPath, relativePath);

        if (file == null || !Files.isRegularFile(file)) {
            sendError(response, 404, "File not found or path is not allowed.");
            return;
        }

        response.setContentType("application/json");

        String content = Files.readString(file, StandardCharsets.UTF_8);

        response.getWriter().write(
                "{\"path\":\"" + escapeJson(relativePath)
                        + "\",\"content\":\"" + escapeJson(content) + "\"}"
        );
    }

    @Override
    protected void doPost(
            HttpServletRequest request,
            HttpServletResponse response
    ) throws IOException {

        configureResponse(response);
        response.setContentType("application/json");

        Path repositoryPath = RepositoryManager.getRepositoryPath();
        Repository repository = new Repository(repositoryPath);

        if (!repository.isInitialized()) {
            sendError(response, 400, "MiniGit repository is not initialized.");
            return;
        }

        String relativePath = request.getParameter("path");
        String content = request.getParameter("content");

        if (relativePath == null || relativePath.isBlank() || content == null) {
            sendError(response, 400, "Both path and content are required.");
            return;
        }

        Path file = resolveSafeFile(repositoryPath, relativePath);

        if (file == null) {
            sendError(response, 400, "Invalid or restricted file path.");
            return;
        }

        // Only allow editing existing regular files.
        if (!Files.isRegularFile(file)) {
            sendError(response, 404, "File does not exist.");
            return;
        }

        Files.writeString(file, content, StandardCharsets.UTF_8);

        response.getWriter().write(
                "{\"success\":true,\"message\":\"File saved successfully.\"}"
        );
    }

    private void listFiles(
            Path repositoryPath,
            HttpServletResponse response
    ) throws IOException {

        response.setContentType("application/json");

        try (var paths = Files.walk(repositoryPath)) {
            String files = paths
                    .filter(Files::isRegularFile)
                    .filter(path -> !path.startsWith(
                            repositoryPath.resolve(".minigit")
                    ))
                    .filter(path -> !path.startsWith(
                            repositoryPath.resolve(".git")
                    ))
                    .sorted(Comparator.naturalOrder())
                    .map(path -> "\"" + escapeJson(
                            repositoryPath.relativize(path)
                                    .toString()
                                    .replace('\\', '/')
                    ) + "\"")
                    .collect(Collectors.joining(","));

            response.getWriter().write("{\"files\":[" + files + "]}");
        }
    }

    private Path resolveSafeFile(Path repositoryPath, String relativePath) {
        try {
            Path relative = Path.of(relativePath);

            if (relative.isAbsolute()) {
                return null;
            }

            Path normalized = repositoryPath.resolve(relative).normalize();

            if (!normalized.startsWith(repositoryPath)) {
                return null;
            }

            if (normalized.equals(repositoryPath.resolve(".minigit"))
                    || normalized.startsWith(
                            repositoryPath.resolve(".minigit"))) {
                return null;
            }

            if (normalized.equals(repositoryPath.resolve(".git"))
                    || normalized.startsWith(
                            repositoryPath.resolve(".git"))) {
                return null;
            }

            // Prevent access through a symbolic link that escapes the repo.
            Path realRepository = repositoryPath.toRealPath();
            Path realFile = normalized.toRealPath();

            if (!realFile.startsWith(realRepository)) {
                return null;
            }

            return realFile;
        } catch (IOException | RuntimeException exception) {
            return null;
        }
    }

    private void sendError(
            HttpServletResponse response,
            int status,
            String message
    ) throws IOException {

        response.setStatus(status);
        response.setContentType("application/json");
        response.getWriter().write(
                "{\"success\":false,\"message\":\""
                        + escapeJson(message) + "\"}"
        );
    }

    private String escapeJson(String value) {
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
package com.minigit.api;

import com.minigit.command.CommitCommand;
import com.minigit.core.Repository;
import com.minigit.core.RepositoryLock;
import com.minigit.core.RepositoryManager;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

@WebServlet("/api/commit")
public class CommitServlet extends HttpServlet {

    private void setCorsHeaders(HttpServletResponse response) {
        response.setHeader(
                "Access-Control-Allow-Origin",
                "http://localhost:5173"
        );
        response.setHeader(
                "Access-Control-Allow-Methods",
                "POST, OPTIONS"
        );
        response.setHeader(
                "Access-Control-Allow-Headers",
                "Content-Type"
        );
    }

    @Override
    protected void doOptions(
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        setCorsHeaders(response);
        response.setStatus(HttpServletResponse.SC_NO_CONTENT);
    }

    @Override
    protected void doPost(
            HttpServletRequest request,
            HttpServletResponse response
    ) throws ServletException, IOException {

        setCorsHeaders(response);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");

        String message = request.getParameter("message");

        try {
            Repository repository = new Repository(RepositoryManager.getRepositoryPath());

            CommitCommand commitCommand = new CommitCommand(repository);

            synchronized (RepositoryLock.LOCK) {
                commitCommand.execute(message);
            }

            Path metadataDirectory = repository.getMetadataDirectory();

            String head = Files.readString(
                    metadataDirectory.resolve("HEAD"),
                    StandardCharsets.UTF_8
            ).trim();

            if (!head.startsWith("ref: ")) {
                throw new IOException("Invalid HEAD file.");
            }

            Path branchFile = metadataDirectory
                    .resolve(head.substring(5))
                    .normalize();

            if (!branchFile.startsWith(
                    metadataDirectory.resolve("refs").normalize()
            )) {
                throw new IOException("Invalid branch reference.");
            }

            String commitHash = Files.readString(
                    branchFile,
                    StandardCharsets.UTF_8
            ).trim();

            String branch = head.substring(
                    "ref: refs/heads/".length()
            );

            response.setStatus(HttpServletResponse.SC_OK);
            response.getWriter().write(
                    "{\"success\":true,"
                            + "\"message\":\"Commit created successfully\","
                            + "\"commitHash\":\"" + escapeJson(commitHash) + "\","
                            + "\"branch\":\"" + escapeJson(branch) + "\","
                            + "\"commitMessage\":\""
                            + escapeJson(message) + "\"}"
            );

        } catch (IllegalArgumentException | IllegalStateException e) {

            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            response.getWriter().write(
                    "{\"success\":false,\"message\":\""
                            + escapeJson(e.getMessage())
                            + "\"}"
            );

        } catch (IOException e) {

            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            response.getWriter().write(
                    "{\"success\":false,\"message\":\""
                            + escapeJson(e.getMessage())
                            + "\"}"
            );
        }
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
package com.minigit.api;

import com.minigit.command.AddCommand;
import com.minigit.core.Repository;
import com.minigit.core.RepositoryLock;
import com.minigit.core.RepositoryManager;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

@WebServlet("/api/add")
public class AddServlet extends HttpServlet {

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

        String filePath = request.getParameter("path");

        try {
            Repository repository = new Repository(RepositoryManager.getRepositoryPath());

            AddCommand addCommand = new AddCommand(repository);

            synchronized (RepositoryLock.LOCK) {
                addCommand.execute(filePath);
            }

            response.setStatus(HttpServletResponse.SC_OK);
            response.getWriter().write(
                    "{\"success\":true,\"message\":\"File staged successfully\"}"
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
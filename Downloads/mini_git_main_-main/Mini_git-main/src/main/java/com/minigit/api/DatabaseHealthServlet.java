package com.minigit.api;

import com.minigit.database.DatabaseSessionFactory;

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

@WebServlet("/api/database/health")
public class DatabaseHealthServlet extends HttpServlet {

    @Override
    protected void doOptions(
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        ApiSupport.preflight(response, "GET, OPTIONS");
    }

    @Override
    protected void doGet(
            HttpServletRequest request,
            HttpServletResponse response
    ) throws IOException {
        ApiSupport.cors(response, "GET, OPTIONS");
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");

        try {
            DatabaseSessionFactory.shared().verifyConnection();
            response.setStatus(HttpServletResponse.SC_OK);
            response.getWriter().write(
                    "{\"success\":true,\"status\":\"UP\","
                            + "\"database\":\"H2\"}"
            );
        } catch (RuntimeException failure) {
            getServletContext().log(
                    "MiniGit database health check failed.",
                    failure
            );
            response.setStatus(
                    HttpServletResponse.SC_SERVICE_UNAVAILABLE
            );
            response.getWriter().write(
                    "{\"success\":false,\"status\":\"DOWN\","
                            + "\"message\":"
                            + ApiSupport.quote(failure.getMessage())
                            + "}"
            );
        }
    }
}

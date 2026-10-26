package com.minigit.api;

import com.minigit.database.DatabaseSessionFactory;
import com.minigit.database.WorkspacePreference;
import com.minigit.database.WorkspacePreferenceRepository;

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

@WebServlet("/api/preferences")
public class WorkspacePreferenceServlet extends HttpServlet {

    @Override
    protected void doOptions(
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        ApiSupport.preflight(response, "GET, POST, OPTIONS");
    }

    @Override
    protected void doGet(
            HttpServletRequest request,
            HttpServletResponse response
    ) throws IOException {
        ApiSupport.cors(response, "GET, POST, OPTIONS");
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");

        try {
            WorkspacePreferenceRepository preferences = repository();
            String key = request.getParameter("key");

            if (key != null) {
                Optional<WorkspacePreference> preference =
                        preferences.findByKey(key);
                if (preference.isEmpty()) {
                    response.setStatus(HttpServletResponse.SC_NOT_FOUND);
                    response.getWriter().write(
                            "{\"success\":false,\"message\":"
                                    + ApiSupport.quote(
                                            "Preference not found."
                                    )
                                    + "}"
                    );
                    return;
                }
                response.getWriter().write(
                        "{\"success\":true,\"preference\":"
                                + preferenceJson(preference.get()) + "}"
                );
                return;
            }

            List<WorkspacePreference> values = preferences.findAll();
            StringBuilder json = new StringBuilder(
                    "{\"success\":true,\"preferences\":["
            );
            for (int index = 0; index < values.size(); index++) {
                if (index > 0) {
                    json.append(',');
                }
                json.append(preferenceJson(values.get(index)));
            }
            response.getWriter().write(json.append("]}").toString());
        } catch (IllegalArgumentException failure) {
            sendError(response, HttpServletResponse.SC_BAD_REQUEST, failure);
        } catch (RuntimeException failure) {
            logFailure(failure);
            sendError(
                    response,
                    HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    failure
            );
        }
    }

    @Override
    protected void doPost(
            HttpServletRequest request,
            HttpServletResponse response
    ) throws IOException {
        ApiSupport.cors(response, "GET, POST, OPTIONS");
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");

        try {
            WorkspacePreference preference = repository().save(
                    request.getParameter("key"),
                    request.getParameter("value")
            );
            response.setStatus(HttpServletResponse.SC_OK);
            response.getWriter().write(
                    "{\"success\":true,\"preference\":"
                            + preferenceJson(preference) + "}"
            );
        } catch (IllegalArgumentException failure) {
            sendError(response, HttpServletResponse.SC_BAD_REQUEST, failure);
        } catch (RuntimeException failure) {
            logFailure(failure);
            sendError(
                    response,
                    HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    failure
            );
        }
    }

    private WorkspacePreferenceRepository repository() {
        return new WorkspacePreferenceRepository(
                DatabaseSessionFactory.shared().sessionFactory()
        );
    }

    private String preferenceJson(WorkspacePreference preference) {
        return "{\"key\":" + ApiSupport.quote(preference.getPreferenceKey())
                + ",\"value\":"
                + ApiSupport.quote(preference.getPreferenceValue())
                + ",\"updatedAt\":"
                + ApiSupport.quote(preference.getUpdatedAt().toString())
                + "}";
    }

    private void sendError(
            HttpServletResponse response,
            int status,
            RuntimeException failure
    ) throws IOException {
        response.setStatus(status);
        response.getWriter().write(
                "{\"success\":false,\"message\":"
                        + ApiSupport.quote(failure.getMessage()) + "}"
        );
    }

    private void logFailure(RuntimeException failure) {
        getServletContext().log(
                "MiniGit workspace preference request failed.",
                failure
        );
    }
}

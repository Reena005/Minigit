package com.minigit.api;

import com.minigit.core.Reflog;
import com.minigit.core.Repository;
import com.minigit.core.RepositoryManager;

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.List;

/**
 * Module 10 - recovery log.
 *
 * GET /api/reflog[?limit=50]   newest entries first
 */
@WebServlet("/api/reflog")
public class ReflogServlet extends HttpServlet {

    private static final String METHODS = "GET, OPTIONS";
    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 500;

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

        String limitParameter = request.getParameter("limit");

        ApiSupport.handle(response, METHODS, () -> {

            int limit = DEFAULT_LIMIT;

            if (limitParameter != null && !limitParameter.isBlank()) {
                try {
                    limit = Math.min(
                            MAX_LIMIT,
                            Math.max(1, Integer.parseInt(limitParameter))
                    );
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException(
                            "limit must be a number."
                    );
                }
            }

            Repository repository =
                    new Repository(RepositoryManager.getRepositoryPath());

            if (!repository.isInitialized()) {
                throw new IllegalStateException(
                        "Repository is not initialized."
                );
            }

            List<Reflog.Entry> entries =
                    Reflog.read(repository.getMetadataDirectory(), limit);

            StringBuilder json =
                    new StringBuilder("{\"success\":true,\"entries\":[");

            for (int i = 0; i < entries.size(); i++) {

                Reflog.Entry entry = entries.get(i);

                if (i > 0) {
                    json.append(',');
                }

                json.append("{\"timestamp\":")
                        .append(ApiSupport.quote(entry.timestamp()))
                        .append(",\"action\":")
                        .append(ApiSupport.quote(entry.action()))
                        .append(",\"branch\":")
                        .append(ApiSupport.quote(entry.branch()))
                        .append(",\"oldHash\":")
                        .append(ApiSupport.quote(entry.oldHash()))
                        .append(",\"newHash\":")
                        .append(ApiSupport.quote(entry.newHash()))
                        .append(",\"detail\":")
                        .append(ApiSupport.quote(entry.detail()))
                        .append('}');
            }

            return json.append("]}").toString();
        });
    }
}

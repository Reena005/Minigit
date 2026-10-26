package com.minigit.api;

import com.minigit.exception.BranchNotFoundException;
import com.minigit.exception.CheckoutConflictException;

import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.List;

/**
 * Small helper shared by the branch / staging / undo servlets: CORS
 * headers, JSON output and a single place that turns exceptions into HTTP
 * status codes.
 */
final class ApiSupport {

    static final String ALLOWED_ORIGIN = "http://localhost:5173";

    /** A request handler that returns a JSON body. */
    @FunctionalInterface
    interface Action {
        String run() throws IOException;
    }

    private ApiSupport() {
        // Prevent instantiation.
    }

    static void cors(HttpServletResponse response, String methods) {
        response.setHeader("Access-Control-Allow-Origin", ALLOWED_ORIGIN);
        response.setHeader("Access-Control-Allow-Methods", methods);
        response.setHeader("Access-Control-Allow-Headers", "Content-Type");
    }

    static void preflight(HttpServletResponse response, String methods) {
        cors(response, methods);
        response.setStatus(HttpServletResponse.SC_NO_CONTENT);
    }

    /** Runs the action and writes its JSON, or a JSON error on failure. */
    static void handle(
            HttpServletResponse response,
            String methods,
            Action action
    ) throws IOException {

        cors(response, methods);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");

        try {
            String body = action.run();
            response.setStatus(HttpServletResponse.SC_OK);
            response.getWriter().write(body);

        } catch (CheckoutConflictException e) {
            error(response, HttpServletResponse.SC_CONFLICT,
                    e.getMessage(),
                    "\"conflicts\":" + stringArray(e.getConflicts()));

        } catch (BranchNotFoundException e) {
            error(response, HttpServletResponse.SC_NOT_FOUND,
                    e.getMessage(), null);

        } catch (IllegalArgumentException e) {
            error(response, HttpServletResponse.SC_BAD_REQUEST,
                    e.getMessage(), null);

        } catch (IllegalStateException e) {
            error(response, HttpServletResponse.SC_CONFLICT,
                    e.getMessage(), null);

        } catch (IOException e) {
            error(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    e.getMessage(), null);
        }
    }

    private static void error(
            HttpServletResponse response,
            int status,
            String message,
            String extraJsonField
    ) throws IOException {

        response.setStatus(status);

        response.getWriter().write(
                "{\"success\":false,\"message\":" + quote(message)
                        + (extraJsonField == null
                        ? ""
                        : "," + extraJsonField)
                        + "}"
        );
    }

    static boolean isTrue(String value) {
        return "true".equalsIgnoreCase(value) || "1".equals(value);
    }

    static String stringArray(List<String> values) {
        StringBuilder json = new StringBuilder("[");

        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append(quote(values.get(i)));
        }

        return json.append(']').toString();
    }

    /** Returns the value as a quoted, escaped JSON string. */
    static String quote(String value) {

        if (value == null) {
            return "\"\"";
        }

        StringBuilder json = new StringBuilder("\"");

        for (char c : value.toCharArray()) {
            switch (c) {
                case '\\' -> json.append("\\\\");
                case '"' -> json.append("\\\"");
                case '\n' -> json.append("\\n");
                case '\r' -> json.append("\\r");
                case '\t' -> json.append("\\t");
                default -> {
                    if (c < 0x20) {
                        json.append(String.format("\\u%04x", (int) c));
                    } else {
                        json.append(c);
                    }
                }
            }
        }

        return json.append('"').toString();
    }
}

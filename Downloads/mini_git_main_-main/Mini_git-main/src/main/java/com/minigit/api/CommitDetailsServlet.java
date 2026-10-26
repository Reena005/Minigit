package com.minigit.api;

import com.minigit.core.Repository;
import com.minigit.core.RepositoryManager;
import com.minigit.core.CommitStore;
import com.minigit.core.RenameDetector;
import com.minigit.storage.FileObjectStore;

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

@WebServlet(urlPatterns = {"/api/commit-details", "/api/diff"})
public class CommitDetailsServlet extends HttpServlet {

    private static final String HASH_PATTERN = "[0-9a-f]{64}";

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
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/json");

        try {
            Repository repository = new Repository(RepositoryManager.getRepositoryPath());

            if (!repository.isInitialized()) {
                sendError(
                        response,
                        HttpServletResponse.SC_BAD_REQUEST,
                        "MiniGit repository is not initialized."
                );
                return;
            }

            FileObjectStore store =
                    new FileObjectStore(repository.getMetadataDirectory());
            CommitStore commitStore =
                    new CommitStore(repository.getMetadataDirectory());

            String path = request.getServletPath();

            if ("/api/commit-details".equals(path)) {
                String hash = request.getParameter("hash");

                if (!validHash(hash)) {
                    sendError(
                            response,
                            HttpServletResponse.SC_BAD_REQUEST,
                            "A valid 64-character commit hash is required."
                    );
                    return;
                }

                Map<String, Object> commit = readCommit(store, hash);
                Map<String, Object> parent = null;

                String parentHash = (String) commit.get("parent");

                if (!parentHash.isEmpty()) {
                    parent = readCommit(store, parentHash);
                }

                commit.put(
                        "changedFiles",
                        calculateChanges(commitStore, commit, parent)
                );

                writeJson(response, commit);
                return;
            }

            if ("/api/diff".equals(path)) {
                String hash = request.getParameter("hash");
                String filePath = request.getParameter("path");

                if (!validHash(hash)) {
                    sendError(
                            response,
                            HttpServletResponse.SC_BAD_REQUEST,
                            "A valid 64-character commit hash is required."
                    );
                    return;
                }

                if (filePath == null || filePath.isBlank()) {
                    sendError(
                            response,
                            HttpServletResponse.SC_BAD_REQUEST,
                            "A file path is required."
                    );
                    return;
                }

                CommitStore.CommitData commit = commitStore.readCommit(hash);
                String parentHash = commit.parent();
                Map<String, String> currentFiles =
                        commitStore.readSnapshot(hash);
                Map<String, String> parentFiles =
                        commitStore.readSnapshot(parentHash);
                Map<String, String> renames =
                        detectRenames(parentFiles, currentFiles);

                String currentBlob = currentFiles.get(filePath);
                String parentBlob = parentFiles.get(filePath);
                String renameFrom = renames.get(filePath);

                if (parentBlob == null && renameFrom != null) {
                    parentBlob = parentFiles.get(renameFrom);
                }

                if (currentBlob == null && parentBlob == null) {
                    sendError(
                            response,
                            HttpServletResponse.SC_NOT_FOUND,
                            "The requested file is not present in this commit or its parent."
                    );
                    return;
                }

                String before = parentBlob == null
                        ? ""
                        : new String(
                                store.read(parentBlob),
                                StandardCharsets.UTF_8
                        );

                String after = currentBlob == null
                        ? ""
                        : new String(
                                store.read(currentBlob),
                                StandardCharsets.UTF_8
                        );

                Map<String, Object> result = new LinkedHashMap<>();
                result.put("success", true);
                result.put("commitHash", hash);
                result.put("path", filePath);
                result.put("status", renameFrom != null
                        ? "RENAMED"
                        : parentBlob == null
                        ? "ADDED"
                        : currentBlob == null
                        ? "DELETED"
                        : parentBlob.equals(currentBlob)
                        ? "UNCHANGED"
                        : "MODIFIED");
                if (renameFrom != null) {
                    result.put("renameFrom", renameFrom);
                }
                result.put("oldContent", before);
                result.put("newContent", after);
                result.put("lines", buildDiff(before, after));

                writeJson(response, result);
                return;
            }

            sendError(
                    response,
                    HttpServletResponse.SC_NOT_FOUND,
                    "Unknown endpoint."
            );

        } catch (IllegalArgumentException e) {
            sendError(
                    response,
                    HttpServletResponse.SC_BAD_REQUEST,
                    e.getMessage()
            );

        } catch (IOException e) {
            getServletContext().log("MiniGit commit API failed.", e);
            sendError(
                    response,
                    HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    e.getMessage()
            );
        }
    }

    private Map<String, Object> readCommit(
            FileObjectStore store,
            String hash
    ) throws IOException {

        if (!validHash(hash)) {
            throw new IOException("Invalid commit hash.");
        }

        String content = new String(
                store.read(hash),
                StandardCharsets.UTF_8
        );

        String[] lines = content.split("\n", -1);

        if (lines.length == 0
                || (!"MINIGIT_COMMIT_V1".equals(lines[0])
                && !"MINIGIT_COMMIT_V2".equals(lines[0]))) {
            throw new IOException("Invalid commit object: " + hash);
        }

        String parent = null;
        String timestamp = null;
        String encodedMessage = null;
        int fileCount = -1;
        int index = 1;

        while (index < lines.length) {
            String line = lines[index++];

            if (line.startsWith("parent=")) {
                parent = line.substring("parent=".length());
            } else if (line.startsWith("timestamp=")) {
                timestamp = line.substring("timestamp=".length());
            } else if (line.startsWith("message=")) {
                encodedMessage = line.substring("message=".length());
            } else if (line.startsWith("files=")) {
                try {
                    fileCount = Integer.parseInt(
                            line.substring("files=".length())
                    );
                } catch (NumberFormatException e) {
                    throw new IOException("Invalid commit file count.", e);
                }
                break;
            } else {
                throw new IOException("Invalid commit metadata.");
            }
        }

        if (parent == null
                || timestamp == null
                || encodedMessage == null
                || fileCount < 0) {
            throw new IOException("Commit metadata is incomplete.");
        }

        if (!parent.isEmpty() && !validHash(parent)) {
            throw new IOException("Invalid parent commit hash.");
        }

        Instant instant;

        try {
            instant = Instant.parse(timestamp);
        } catch (RuntimeException e) {
            throw new IOException("Invalid commit timestamp.", e);
        }

        String message;

        try {
            message = new String(
                    Base64.getDecoder().decode(encodedMessage),
                    StandardCharsets.UTF_8
            );
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid commit message encoding.", e);
        }

        Map<String, String> files = new TreeMap<>();

        for (int i = 0; i < fileCount; i++) {
            if (index >= lines.length) {
                throw new IOException("Missing commit file entry.");
            }

            String entry = lines[index++];
            int separator = entry.lastIndexOf('\t');

            if (separator <= 0) {
                throw new IOException("Invalid commit file entry.");
            }

            String filePath;

            try {
                filePath = new String(
                        Base64.getDecoder().decode(
                                entry.substring(0, separator)
                        ),
                        StandardCharsets.UTF_8
                );
            } catch (IllegalArgumentException e) {
                throw new IOException("Invalid file path encoding.", e);
            }

            String blobHash = entry.substring(separator + 1);

            if (filePath.isBlank()
                    || filePath.startsWith("/")
                    || filePath.contains("\\")
                    || Path.of(filePath).isAbsolute()
                    || Path.of(filePath).normalize().startsWith("..")) {
                throw new IOException("Invalid repository file path.");
            }

            if (!validHash(blobHash)) {
                throw new IOException("Invalid blob hash.");
            }

            if (files.put(filePath, blobHash) != null) {
                throw new IOException("Duplicate file in commit.");
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("hash", hash);
        result.put("parent", parent);
        result.put("timestamp", instant.toString());
        result.put("message", message);
        result.put("fileCount", files.size());
        result.put("files", files);

        return result;
    }

    private List<Map<String, String>> calculateChanges(
            CommitStore commitStore,
            Map<String, Object> commit,
            Map<String, Object> parent
    ) throws IOException {

        Map<String, String> current = commitStore.readSnapshot(
                (String) commit.get("hash")
        );
        Map<String, String> previous = parent == null
                ? Map.of()
                : commitStore.readSnapshot((String) parent.get("hash"));
        Map<String, String> added = new TreeMap<>();
        Map<String, String> deleted = new TreeMap<>();

        current.forEach((path, hash) -> {
            if (!previous.containsKey(path)) {
                added.put(path, hash);
            }
        });
        previous.forEach((path, hash) -> {
            if (!current.containsKey(path)) {
                deleted.put(path, hash);
            }
        });

        Map<String, String> renames = RenameDetector.detect(deleted, added);

        List<Map<String, String>> changes = new ArrayList<>();

        for (String path : current.keySet()) {
            String oldHash = previous.get(path);
            String newHash = current.get(path);

            String status = oldHash == null
                    ? renames.containsKey(path)
                            ? "RENAMED"
                            : "ADDED"
                    : oldHash.equals(newHash)
                    ? null
                    : "MODIFIED";

            if (status != null) {
                Map<String, String> item = new LinkedHashMap<>();
                item.put("path", path);
                item.put("status", status);
                if (renames.containsKey(path)) {
                    item.put("oldPath", renames.get(path));
                }
                changes.add(item);
            }
        }

        for (String path : previous.keySet()) {
            if (!current.containsKey(path)
                    && !renames.containsValue(path)) {
                Map<String, String> item = new LinkedHashMap<>();
                item.put("path", path);
                item.put("status", "DELETED");
                changes.add(item);
            }
        }

        return changes;
    }

    private Map<String, String> detectRenames(
            Map<String, String> previous,
            Map<String, String> current
    ) {
        Map<String, String> added = new TreeMap<>();
        Map<String, String> deleted = new TreeMap<>();

        current.forEach((path, hash) -> {
            if (!previous.containsKey(path)) {
                added.put(path, hash);
            }
        });
        previous.forEach((path, hash) -> {
            if (!current.containsKey(path)) {
                deleted.put(path, hash);
            }
        });

        return RenameDetector.detect(deleted, added);
    }

    private List<Map<String, Object>> buildDiff(
            String before,
            String after
    ) {

        String[] oldLines = before.isEmpty()
                ? new String[0]
                : before.split("\n", -1);

        String[] newLines = after.isEmpty()
                ? new String[0]
                : after.split("\n", -1);

        // Guard against unexpectedly large quadratic diff calculations.
        long cells = (long) (oldLines.length + 1)
                * (newLines.length + 1);

        List<Map<String, Object>> result = new ArrayList<>();

        if (cells > 1_000_000L) {
            addDiffLine(result, "INFO",
                    "Diff too large for inline comparison.");
            return result;
        }

        int[][] dp = new int[oldLines.length + 1][newLines.length + 1];

        for (int i = oldLines.length - 1; i >= 0; i--) {
            for (int j = newLines.length - 1; j >= 0; j--) {
                dp[i][j] = oldLines[i].equals(newLines[j])
                        ? 1 + dp[i + 1][j + 1]
                        : Math.max(dp[i + 1][j], dp[i][j + 1]);
            }
        }

        int i = 0;
        int j = 0;

        while (i < oldLines.length || j < newLines.length) {
            if (i < oldLines.length
                    && j < newLines.length
                    && oldLines[i].equals(newLines[j])) {

                addDiffLine(result, "CONTEXT", oldLines[i]);
                i++;
                j++;

            } else if (i < oldLines.length
                    && (j == newLines.length
                    || dp[i + 1][j] >= dp[i][j + 1])) {

                addDiffLine(result, "DELETION", oldLines[i]);
                i++;

            } else {
                addDiffLine(result, "ADDITION", newLines[j]);
                j++;
            }
        }

        return result;
    }

    private void addDiffLine(
            List<Map<String, Object>> result,
            String type,
            String content
    ) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("type", type);
        line.put("content", content);
        result.add(line);
    }

    private boolean validHash(String hash) {
        return hash != null && hash.matches(HASH_PATTERN);
    }

    private void writeJson(
            HttpServletResponse response,
            Object value
    ) throws IOException {
        response.getWriter().write(toJson(value));
    }

    private String toJson(Object value) {

        if (value == null) {
            return "null";
        }

        if (value instanceof String string) {
            return "\"" + escapeJson(string) + "\"";
        }

        if (value instanceof Number || value instanceof Boolean) {
            return value.toString();
        }

        if (value instanceof Map<?, ?> map) {
            List<String> entries = new ArrayList<>();

            for (Map.Entry<?, ?> entry : map.entrySet()) {
                entries.add(
                        toJson(entry.getKey().toString())
                                + ":" + toJson(entry.getValue())
                );
            }

            return "{" + String.join(",", entries) + "}";
        }

        if (value instanceof Iterable<?> iterable) {
            List<String> entries = new ArrayList<>();

            for (Object item : iterable) {
                entries.add(toJson(item));
            }

            return "[" + String.join(",", entries) + "]";
        }

        return toJson(value.toString());
    }

    private String escapeJson(String value) {

        if (value == null) {
            return "";
        }

        StringBuilder escaped = new StringBuilder();

        for (char c : value.toCharArray()) {
            switch (c) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");

                default -> {
                    if (c < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) c));
                    } else {
                        escaped.append(c);
                    }
                }
            }
        }

        return escaped.toString();
    }

    private void sendError(
            HttpServletResponse response,
            int status,
            String message
    ) throws IOException {

        response.setStatus(status);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", false);
        result.put("message", message);

        writeJson(response, result);
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
    }
}

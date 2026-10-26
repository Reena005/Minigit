package com.minigit.api;

import com.minigit.core.Repository;
import com.minigit.core.RepositoryManager;
import com.minigit.storage.FileObjectStore;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@WebServlet("/api/history")
public class CommitHistoryServlet extends HttpServlet {

    private static final int MAX_COMMITS = 200;

    @Override
    protected void doOptions(
            HttpServletRequest request,
            HttpServletResponse response
    ) throws ServletException, IOException {

        setCorsHeaders(response);
        response.setStatus(HttpServletResponse.SC_NO_CONTENT);
    }

    @Override
    protected void doGet(
            HttpServletRequest request,
            HttpServletResponse response
    ) throws ServletException, IOException {

        setCorsHeaders(response);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");

        try {
            Repository repository = new Repository(
                    RepositoryManager.getRepositoryPath()
            );

            if (!repository.isInitialized()) {
                response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                response.getWriter().write(
                        "{\"success\":false,\"message\":\"Repository is not initialized.\"}"
                );
                return;
            }

            Path metadataDirectory = repository.getMetadataDirectory();
            Path headFile = metadataDirectory.resolve("HEAD");

            if (!Files.isRegularFile(headFile)) {
                throw new IOException("Repository HEAD file is missing.");
            }

            String head = Files.readString(
                    headFile,
                    StandardCharsets.UTF_8
            ).trim();

            if (!head.startsWith("ref: ")) {
                throw new IOException("Invalid HEAD file.");
            }

            String reference = head.substring(5);

            Path branchFile = metadataDirectory
                    .resolve(reference)
                    .normalize();

            Path refsDirectory = metadataDirectory
                    .resolve("refs")
                    .normalize();

            if (!branchFile.startsWith(refsDirectory)) {
                throw new IOException("Invalid branch reference.");
            }

            String branchName = reference.startsWith("refs/heads/")
                    ? reference.substring("refs/heads/".length())
                    : reference;

            if (!Files.isRegularFile(branchFile)) {
                writeSuccess(response, branchName, new ArrayList<>());
                return;
            }

            String currentHash = Files.readString(
                    branchFile,
                    StandardCharsets.UTF_8
            ).trim();

            FileObjectStore objectStore =
                    new FileObjectStore(metadataDirectory);

            List<Map<String, Object>> commits = new ArrayList<>();

            while (!currentHash.isEmpty()
                    && commits.size() < MAX_COMMITS) {

                byte[] objectContent = objectStore.read(currentHash);

                String content = new String(
                        objectContent,
                        StandardCharsets.UTF_8
                );

                Map<String, Object> commit = parseCommit(
                        content,
                        currentHash,
                        branchName
                );

                commits.add(commit);

                Object parentValue = commit.get("parent");
                String parentHash = parentValue == null
                        ? ""
                        : parentValue.toString();

                if (parentHash.isEmpty()) {
                    break;
                }

                if (!parentHash.matches("[0-9a-f]{64}")) {
                    throw new IOException(
                            "Invalid parent commit hash: " + parentHash
                    );
                }

                currentHash = parentHash;
            }

            writeSuccess(response, branchName, commits);

        } catch (IllegalArgumentException e) {

            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);

            response.getWriter().write(
                    "{\"success\":false,\"message\":\""
                            + escapeJson(e.getMessage())
                            + "\"}"
            );

        } catch (IOException e) {

            getServletContext().log(
                    "Unable to read MiniGit commit history.",
                    e
            );

            response.setStatus(
                    HttpServletResponse.SC_INTERNAL_SERVER_ERROR
            );

            response.getWriter().write(
                    "{\"success\":false,\"message\":\""
                            + escapeJson(
                                    "Unable to read commit history: "
                                            + e.getMessage()
                            )
                            + "\"}"
            );
        }
    }

    private Map<String, Object> parseCommit(
            String content,
            String hash,
            String branchName
    ) throws IOException {

        String[] lines = content.split("\n", -1);

        if (lines.length == 0
                || (!"MINIGIT_COMMIT_V1".equals(lines[0])
                && !"MINIGIT_COMMIT_V2".equals(lines[0]))) {

            throw new IOException(
                    "Object is not a valid MiniGit commit: " + hash
            );
        }

        Map<String, String> metadata = new LinkedHashMap<>();

        int index = 1;

        while (index < lines.length) {

            String line = lines[index];

            if (line.startsWith("parent=")
                    || line.startsWith("timestamp=")
                    || line.startsWith("message=")
                    || line.startsWith("files=")) {

                int separator = line.indexOf('=');

                String key = line.substring(0, separator);
                String value = line.substring(separator + 1);

                metadata.put(key, value);
                index++;

                if ("files".equals(key)) {
                    break;
                }

            } else {
                throw new IOException(
                        "Invalid commit metadata in object: " + hash
                );
            }
        }

        String parent = metadata.getOrDefault("parent", "");
        String timestampValue = metadata.get("timestamp");
        String encodedMessage = metadata.get("message");
        String filesValue = metadata.get("files");

        if (timestampValue == null
                || encodedMessage == null
                || filesValue == null) {

            throw new IOException(
                    "Commit is missing required metadata: " + hash
            );
        }

        Instant timestamp;

        try {
            timestamp = Instant.parse(timestampValue);
        } catch (RuntimeException e) {
            throw new IOException(
                    "Invalid timestamp in commit: " + hash,
                    e
            );
        }

        String message;

        try {
            message = new String(
                    Base64.getDecoder().decode(encodedMessage),
                    StandardCharsets.UTF_8
            );
        } catch (IllegalArgumentException e) {
            throw new IOException(
                    "Invalid commit message encoding: " + hash,
                    e
            );
        }

        int fileCount;

        try {
            fileCount = Integer.parseInt(filesValue);

            if (fileCount < 0) {
                throw new NumberFormatException("Negative file count");
            }

        } catch (NumberFormatException e) {
            throw new IOException(
                    "Invalid file count in commit: " + hash,
                    e
            );
        }

        List<Map<String, String>> files = new ArrayList<>();

        for (int i = 0; i < fileCount; i++) {

            if (index >= lines.length) {
                throw new IOException(
                        "Commit contains fewer file entries than expected: "
                                + hash
                );
            }

            String fileLine = lines[index++];
            int separator = fileLine.lastIndexOf('\t');

            if (separator <= 0) {
                throw new IOException(
                        "Invalid file entry in commit: " + hash
                );
            }

            String encodedPath = fileLine.substring(0, separator);
            String fileHash = fileLine.substring(separator + 1);

            String path;

            try {
                path = new String(
                        Base64.getDecoder().decode(encodedPath),
                        StandardCharsets.UTF_8
                );
            } catch (IllegalArgumentException e) {
                throw new IOException(
                        "Invalid file path encoding in commit: " + hash,
                        e
                );
            }

            if (!fileHash.matches("[0-9a-f]{64}")) {
                throw new IOException(
                        "Invalid file object hash in commit: " + hash
                );
            }

            Map<String, String> file = new LinkedHashMap<>();
            file.put("path", path);
            file.put("hash", fileHash);

            files.add(file);
        }

        Map<String, Object> result = new LinkedHashMap<>();

        result.put("hash", hash);
        result.put("parent", parent);
        result.put("timestamp", timestamp.toString());
        result.put("message", message);
        result.put("branch", branchName);
        result.put("files", files);
        result.put("fileCount", fileCount);

        return result;
    }

    private void writeSuccess(
            HttpServletResponse response,
            String branchName,
            List<Map<String, Object>> commits
    ) throws IOException {

        Map<String, Object> result = new LinkedHashMap<>();

        result.put("success", true);
        result.put("branch", branchName);
        result.put("count", commits.size());
        result.put("commits", commits);

        response.setStatus(HttpServletResponse.SC_OK);
        response.getWriter().write(toJson(result));
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
                                + ":"
                                + toJson(entry.getValue())
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
                        escaped.append(
                                String.format("\\u%04x", (int) c)
                        );
                    } else {
                        escaped.append(c);
                    }
                }
            }
        }

        return escaped.toString();
    }

    private void setCorsHeaders(HttpServletResponse response) {

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

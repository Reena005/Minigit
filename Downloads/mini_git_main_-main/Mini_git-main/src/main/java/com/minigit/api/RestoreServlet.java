package com.minigit.api;

import com.minigit.core.Index;
import com.minigit.core.CommitStore;
import com.minigit.core.Repository;
import com.minigit.core.RepositoryManager;
import com.minigit.storage.FileObjectStore;
import com.minigit.util.HashUtil;

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;

@WebServlet("/api/restore")
public class RestoreServlet extends HttpServlet {

    private static final String HASH_PATTERN = "[0-9a-f]{64}";

    private void setCors(HttpServletResponse response) {
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
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/json");
    }

    @Override
    protected void doOptions(
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        setCors(response);
        response.setStatus(HttpServletResponse.SC_NO_CONTENT);
    }

    @Override
    protected void doPost(
            HttpServletRequest request,
            HttpServletResponse response
    ) throws IOException {

        setCors(response);

        try {
            Repository repository =
                    new Repository(RepositoryManager.getRepositoryPath());

            if (!repository.isInitialized()) {
                sendError(
                        response,
                        HttpServletResponse.SC_BAD_REQUEST,
                        "MiniGit repository is not initialized."
                );
                return;
            }

            /*
             * Match the existing AddServlet and FileServlet conventions:
             * form-encoded parameters named commitHash and path.
             */
            String commitHash = request.getParameter("commitHash");
            String filePath = request.getParameter("path");

            if (commitHash == null
                    || !commitHash.matches(HASH_PATTERN)) {
                sendError(
                        response,
                        HttpServletResponse.SC_BAD_REQUEST,
                        "A valid 64-character lowercase commit hash is required."
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

            Path relativePath;

            try {
                relativePath = Path.of(filePath);
            } catch (RuntimeException e) {
                sendError(
                        response,
                        HttpServletResponse.SC_BAD_REQUEST,
                        "Invalid file path."
                );
                return;
            }

            if (!isSafeRelativePath(relativePath, filePath)) {
                sendError(
                        response,
                        HttpServletResponse.SC_BAD_REQUEST,
                        "Invalid or restricted file path."
                );
                return;
            }

            Path repositoryPath = repository.getWorkingDirectory()
                    .toAbsolutePath()
                    .normalize();

            Path target = repositoryPath.resolve(relativePath).normalize();

            if (!target.startsWith(repositoryPath)) {
                sendError(
                        response,
                        HttpServletResponse.SC_BAD_REQUEST,
                        "File path escapes the repository."
                );
                return;
            }

            Path metadataDirectory = repository.getMetadataDirectory();

            FileObjectStore store =
                    new FileObjectStore(metadataDirectory);

            /*
             * Validate the target commit and reconstruct the file snapshot.
             */
            Map<String, String> snapshot = new CommitStore(
                    metadataDirectory
            ).readSnapshot(commitHash);

            String blobHash = snapshot.get(filePath);

            if (blobHash == null) {
                sendError(
                        response,
                        HttpServletResponse.SC_NOT_FOUND,
                        "The file does not exist in the selected commit."
                );
                return;
            }

            /*
             * Do not silently overwrite staged content.
             */
            Index index = new Index(metadataDirectory);

            if (index.getEntries().containsKey(filePath)) {
                sendError(
                        response,
                        HttpServletResponse.SC_CONFLICT,
                        "This file has staged changes. Commit or unstage it before restoring."
                );
                return;
            }

            /*
             * Check whether the file is already present and ensure that
             * existing symlinks cannot redirect writes outside the repo.
             */
            if (Files.exists(
                    target,
                    LinkOption.NOFOLLOW_LINKS
            )) {

                if (Files.isSymbolicLink(target)
                        || !Files.isRegularFile(
                                target,
                                LinkOption.NOFOLLOW_LINKS
                        )) {
                    sendError(
                            response,
                            HttpServletResponse.SC_BAD_REQUEST,
                            "Restore is allowed only for regular files, not symbolic links or directories."
                    );
                    return;
                }

                Path realRepository = repositoryPath.toRealPath();
                Path realTarget = target.toRealPath();

                if (!realTarget.startsWith(realRepository)) {
                    sendError(
                            response,
                            HttpServletResponse.SC_BAD_REQUEST,
                            "File path escapes the repository."
                    );
                    return;
                }

                /*
                 * Refuse to overwrite unstaged local modifications.
                 * Restore is safe when the working file matches HEAD.
                 */
                String headHash = resolveHeadFileHash(
                        repository,
                        filePath
                );

                String workingHash = HashUtil.sha256(
                        Files.readAllBytes(target)
                );

                if (headHash == null || !headHash.equals(workingHash)) {
                    sendError(
                            response,
                            HttpServletResponse.SC_CONFLICT,
                            "The working file has local changes. Save or commit those changes before restoring."
                    );
                    return;
                }

            } else {
                /*
                 * A missing tracked file is safe to recreate, provided no
                 * existing parent component is a symbolic link.
                 */
                Path parent = target.getParent();

                if (parent == null
                        || !parent.startsWith(repositoryPath)) {
                    sendError(
                            response,
                            HttpServletResponse.SC_BAD_REQUEST,
                            "Invalid target directory."
                    );
                    return;
                }
            }

            byte[] restoredContent = store.read(blobHash);

            /*
             * Recheck the blob hash before writing. FileObjectStore also
             * verifies object integrity in read().
             */
            if (!HashUtil.sha256(restoredContent).equals(blobHash)) {
                sendError(
                        response,
                        HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                        "Stored file content failed integrity verification."
                );
                return;
            }

            /*
             * Create parent directories only after validating the commit,
             * path and staging state.
             */
            Files.createDirectories(target.getParent());

            Path realRepository = repositoryPath.toRealPath();
            Path realParent = target.getParent().toRealPath();

            if (!realParent.startsWith(realRepository)) {
                sendError(
                        response,
                        HttpServletResponse.SC_BAD_REQUEST,
                        "Target directory escapes the repository."
                );
                return;
            }

            Path temporaryFile = Files.createTempFile(
                    realParent,
                    ".minigit-restore-",
                    ".tmp"
            );

            try {
                Files.write(temporaryFile, restoredContent);

                try {
                    Files.move(
                            temporaryFile,
                            target,
                            StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING
                    );
                } catch (IOException e) {
                    Files.move(
                            temporaryFile,
                            target,
                            StandardCopyOption.REPLACE_EXISTING
                    );
                }
            } finally {
                Files.deleteIfExists(temporaryFile);
            }

            response.setStatus(HttpServletResponse.SC_OK);

            response.getWriter().write(
                    "{\"success\":true,"
                            + "\"message\":\"File restored successfully.\","
                            + "\"path\":\"" + escapeJson(filePath) + "\","
                            + "\"commitHash\":\"" + commitHash + "\"}"
            );

        } catch (IllegalArgumentException e) {
            sendError(
                    response,
                    HttpServletResponse.SC_BAD_REQUEST,
                    e.getMessage()
            );

        } catch (IllegalStateException e) {
            sendError(
                    response,
                    HttpServletResponse.SC_CONFLICT,
                    e.getMessage()
            );

        } catch (IOException e) {
            getServletContext().log("MiniGit restore API failed.", e);

            sendError(
                    response,
                    HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    "Restore failed: " + e.getMessage()
            );
        }
    }

    private boolean isSafeRelativePath(
            Path relativePath,
            String filePath
    ) {
        if (relativePath.isAbsolute()
                || relativePath.normalize().startsWith("..")
                || filePath.startsWith("/")
                || filePath.contains("\\")
                || filePath.contains("\u0000")) {
            return false;
        }

        Path normalized = relativePath.normalize();

        if (normalized.toString().isBlank()
                || normalized.toString().equals(".")
                || normalized.startsWith(".minigit")
                || normalized.startsWith(".git")) {
            return false;
        }

        return true;
    }

    /*
     * Determine the version of a path currently recorded by HEAD.
     */
    private String resolveHeadFileHash(
            Repository repository,
            String filePath
    ) throws IOException {

        Path headFile = repository.getMetadataDirectory().resolve("HEAD");

        String head = Files.readString(
                headFile,
                StandardCharsets.UTF_8
        ).trim();

        if (!head.startsWith("ref: ")) {
            throw new IOException("Invalid HEAD file.");
        }

        String reference = head.substring(5);

        Path refsDirectory = repository.getMetadataDirectory()
                .resolve("refs")
                .toAbsolutePath()
                .normalize();

        Path branchFile = repository.getMetadataDirectory()
                .resolve(reference)
                .toAbsolutePath()
                .normalize();

        if (!branchFile.startsWith(refsDirectory)) {
            throw new IOException("Invalid branch reference in HEAD.");
        }

        if (!Files.isRegularFile(branchFile)) {
            return null;
        }

        String headHash = Files.readString(
                branchFile,
                StandardCharsets.UTF_8
        ).trim();

        if (headHash.isEmpty()) {
            return null;
        }

        return new CommitStore(
                repository.getMetadataDirectory()
        ).readSnapshot(headHash).get(filePath);
    }

    private void sendError(
            HttpServletResponse response,
            int status,
            String message
    ) throws IOException {

        response.setStatus(status);

        response.getWriter().write(
                "{\"success\":false,\"message\":\""
                        + escapeJson(message) + "\"}"
        );
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
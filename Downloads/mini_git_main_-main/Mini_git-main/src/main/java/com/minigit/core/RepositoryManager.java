package com.minigit.core;

import java.nio.file.Path;

public final class RepositoryManager {

    private static final String DEFAULT_REPOSITORY_NAME =
            "mini-git-repository";

    private RepositoryManager() {
        // Prevent instantiation.
    }

    public static Path getRepositoryPath() {
        String configuredPath =
                System.getProperty("minigit.repository");

        if (configuredPath != null && !configuredPath.isBlank()) {
            return Path.of(configuredPath)
                    .toAbsolutePath()
                    .normalize();
        }

        return Path.of(
                System.getProperty("user.home"),
                DEFAULT_REPOSITORY_NAME
        ).toAbsolutePath().normalize();
    }
}
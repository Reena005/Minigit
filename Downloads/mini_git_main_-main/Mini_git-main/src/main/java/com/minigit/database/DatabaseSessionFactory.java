package com.minigit.database;

import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public final class DatabaseSessionFactory implements AutoCloseable {

    private static final String DATABASE_PATH_PROPERTY =
            "minigit.database.path";
    private static final String DATABASE_PATH_ENVIRONMENT =
            "MINIGIT_DATABASE_PATH";

    private static volatile DatabaseSessionFactory sharedInstance;

    private final Path databasePath;
    private final StandardServiceRegistry serviceRegistry;
    private final SessionFactory sessionFactory;

    private DatabaseSessionFactory(Path databasePath) {
        this.databasePath = databasePath.toAbsolutePath().normalize();
        if (this.databasePath.getFileName() == null) {
            throw new IllegalArgumentException(
                    "MiniGit database path must name a database file."
            );
        }

        try {
            Files.createDirectories(this.databasePath.getParent());
        } catch (IOException | SecurityException e) {
            throw new IllegalStateException(
                    "Cannot create the MiniGit database directory: "
                            + this.databasePath.getParent(),
                    e
            );
        }

        Map<String, Object> settings = Map.of(
                "hibernate.connection.driver_class", "org.h2.Driver",
                "hibernate.connection.url", jdbcUrl(this.databasePath),
                "hibernate.dialect", "org.hibernate.dialect.H2Dialect",
                "hibernate.hbm2ddl.auto", "update",
                "hibernate.show_sql", "false",
                "hibernate.format_sql", "false",
                "hibernate.jdbc.time_zone", "UTC"
        );

        StandardServiceRegistry registry =
                new StandardServiceRegistryBuilder()
                        .applySettings(settings)
                        .build();

        try {
            SessionFactory builtSessionFactory =
                    new MetadataSources(registry)
                            .addAnnotatedClass(WorkspacePreference.class)
                            .buildMetadata()
                            .buildSessionFactory();
            this.serviceRegistry = registry;
            this.sessionFactory = builtSessionFactory;
        } catch (RuntimeException | Error e) {
            StandardServiceRegistryBuilder.destroy(registry);
            throw new IllegalStateException(
                    "Cannot initialize the MiniGit H2 database at "
                            + this.databasePath,
                    e
            );
        }
    }

    public static synchronized DatabaseSessionFactory initializeShared() {
        if (sharedInstance == null) {
            DatabaseSessionFactory initialized =
                    new DatabaseSessionFactory(defaultDatabasePath());
            try {
                initialized.verifyConnection();
                sharedInstance = initialized;
            } catch (RuntimeException failure) {
                try {
                    initialized.close();
                } catch (RuntimeException closeFailure) {
                    failure.addSuppressed(closeFailure);
                }
                throw failure;
            }
        }
        return sharedInstance;
    }

    public static DatabaseSessionFactory shared() {
        DatabaseSessionFactory instance = sharedInstance;
        if (instance == null) {
            throw new IllegalStateException(
                    "MiniGit database has not been initialized."
            );
        }
        return instance;
    }

    static DatabaseSessionFactory openForPath(Path databasePath) {
        return new DatabaseSessionFactory(databasePath);
    }

    public SessionFactory sessionFactory() {
        return sessionFactory;
    }

    public Path databasePath() {
        return databasePath;
    }

    public void verifyConnection() {
        try (Session session = sessionFactory.openSession()) {
            Integer result = session.createNativeQuery(
                            "SELECT 1",
                            Integer.class
                    )
                    .getSingleResult();
            if (result != 1) {
                throw new IllegalStateException(
                        "Unexpected result from H2 connection check."
                );
            }
        } catch (RuntimeException e) {
            throw new IllegalStateException(
                    "Cannot connect to the MiniGit H2 database.",
                    e
            );
        }
    }

    @Override
    public void close() {
        try {
            sessionFactory.close();
        } finally {
            StandardServiceRegistryBuilder.destroy(serviceRegistry);
        }
    }

    public static synchronized void closeShared() {
        if (sharedInstance != null) {
            DatabaseSessionFactory closing = sharedInstance;
            sharedInstance = null;
            closing.close();
        }
    }

    static Path defaultDatabasePath() {
        String configuredPath = System.getProperty(DATABASE_PATH_PROPERTY);
        if (configuredPath == null || configuredPath.isBlank()) {
            configuredPath = System.getenv(DATABASE_PATH_ENVIRONMENT);
        }
        if (configuredPath != null && !configuredPath.isBlank()) {
            return Path.of(configuredPath).toAbsolutePath().normalize();
        }
        return Path.of(
                System.getProperty("user.home"),
                ".minigit",
                "database",
                "minigit"
        ).toAbsolutePath().normalize();
    }

    private static String jdbcUrl(Path databasePath) {
        String normalized = databasePath.toString().replace('\\', '/');
        return "jdbc:h2:file:" + normalized + ";DB_CLOSE_ON_EXIT=FALSE";
    }
}

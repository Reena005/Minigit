package com.minigit.database;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabasePersistenceTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void connectsAndPersistsPreferencesAcrossFactoryRestart() {
        Path databaseFile = temporaryDirectory
                .resolve("persistent")
                .resolve("minigit");

        try (DatabaseSessionFactory database =
                     DatabaseSessionFactory.openForPath(databaseFile)) {
            database.verifyConnection();

            WorkspacePreference saved =
                    new WorkspacePreferenceRepository(
                            database.sessionFactory()
                    ).save("ui.density", "compact");

            assertEquals("ui.density", saved.getPreferenceKey());
            assertEquals("compact", saved.getPreferenceValue());
            assertNotNull(saved.getUpdatedAt());
        }

        try (DatabaseSessionFactory database =
                     DatabaseSessionFactory.openForPath(databaseFile)) {
            WorkspacePreference loaded =
                    new WorkspacePreferenceRepository(
                            database.sessionFactory()
                    ).findByKey("ui.density")
                            .orElseThrow();

            assertEquals("compact", loaded.getPreferenceValue());
            assertTrue(databasePathExists(databaseFile));
        }
    }

    @Test
    void rollsBackFailedTransactionsAndRemainsUsable() {
        try (DatabaseSessionFactory database =
                     DatabaseSessionFactory.openForPath(
                             temporaryDirectory.resolve("rollback")
                                     .resolve("minigit")
                     )) {
            WorkspacePreferenceRepository repository =
                    new WorkspacePreferenceRepository(
                            database.sessionFactory()
                    );

            IllegalStateException failure = assertThrows(
                    IllegalStateException.class,
                    () -> DatabaseTransactions.execute(
                            database.sessionFactory(),
                            session -> {
                                session.persist(
                                        new WorkspacePreference(
                                                "should.rollback",
                                                "uncommitted"
                                        )
                                );
                                session.flush();
                                throw new IllegalStateException(
                                        "forced transaction failure"
                                );
                            }
                    )
            );

            assertEquals("forced transaction failure", failure.getMessage());
            assertTrue(repository.findByKey("should.rollback").isEmpty());
            database.verifyConnection();
        }
    }

    private boolean databasePathExists(Path databaseFile) {
        return java.nio.file.Files.exists(
                databaseFile.resolveSibling(
                        databaseFile.getFileName() + ".mv.db"
                )
        );
    }
}

package com.minigit.database;

import org.hibernate.Session;
import org.hibernate.SessionFactory;

import java.util.List;
import java.util.Optional;

public final class WorkspacePreferenceRepository {

    private static final int MAX_KEY_LENGTH = 100;
    private static final int MAX_VALUE_LENGTH = 4000;

    private final SessionFactory sessionFactory;

    public WorkspacePreferenceRepository(SessionFactory sessionFactory) {
        this.sessionFactory = sessionFactory;
    }

    public WorkspacePreference save(String key, String value) {
        validate(key, value);

        return DatabaseTransactions.execute(sessionFactory, session -> {
            WorkspacePreference preference = session.find(
                    WorkspacePreference.class,
                    key
            );

            if (preference == null) {
                preference = new WorkspacePreference(key, value);
                session.persist(preference);
            } else {
                preference.updateValue(value);
            }

            session.flush();
            return preference;
        });
    }

    public Optional<WorkspacePreference> findByKey(String key) {
        validateKey(key);
        try (Session session = sessionFactory.openSession()) {
            return Optional.ofNullable(
                    session.find(WorkspacePreference.class, key)
            );
        }
    }

    public List<WorkspacePreference> findAll() {
        try (Session session = sessionFactory.openSession()) {
            return session.createQuery(
                            "from WorkspacePreference "
                                    + "order by preferenceKey",
                            WorkspacePreference.class
                    )
                    .getResultList();
        }
    }

    private void validate(String key, String value) {
        validateKey(key);
        if (value == null || value.length() > MAX_VALUE_LENGTH) {
            throw new IllegalArgumentException(
                    "Preference value is required and must be at most "
                            + MAX_VALUE_LENGTH + " characters."
            );
        }
    }

    private void validateKey(String key) {
        if (key == null || key.isBlank()
                || key.length() > MAX_KEY_LENGTH) {
            throw new IllegalArgumentException(
                    "Preference key is required and must be at most "
                            + MAX_KEY_LENGTH + " characters."
            );
        }
    }
}

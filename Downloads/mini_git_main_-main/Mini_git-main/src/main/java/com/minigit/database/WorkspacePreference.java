package com.minigit.database;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "workspace_preferences")
public class WorkspacePreference {

    @Id
    @Column(name = "preference_key", nullable = false, length = 100)
    private String preferenceKey;

    @Column(name = "preference_value", nullable = false, length = 4000)
    private String preferenceValue;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected WorkspacePreference() {
    }

    WorkspacePreference(String preferenceKey, String preferenceValue) {
        this.preferenceKey = preferenceKey;
        this.preferenceValue = preferenceValue;
        this.updatedAt = Instant.now();
    }

    public String getPreferenceKey() {
        return preferenceKey;
    }

    public String getPreferenceValue() {
        return preferenceValue;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    void updateValue(String value) {
        preferenceValue = value;
        updatedAt = Instant.now();
    }
}

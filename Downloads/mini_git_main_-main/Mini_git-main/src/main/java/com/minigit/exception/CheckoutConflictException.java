package com.minigit.exception;

import java.util.List;

/**
 * Thrown when switching branches would overwrite or discard local work.
 * The list of conflicting paths is available to callers (CLI and API).
 */
public class CheckoutConflictException extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    private final transient List<String> conflicts;

    public CheckoutConflictException(String message, List<String> conflicts) {
        super(message);
        this.conflicts = List.copyOf(conflicts);
    }

    public List<String> getConflicts() {
        return conflicts;
    }
}

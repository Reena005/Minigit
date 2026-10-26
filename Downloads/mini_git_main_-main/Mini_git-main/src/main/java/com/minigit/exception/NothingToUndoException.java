package com.minigit.exception;

/**
 * Thrown when an undo operation is requested but there is nothing to undo.
 */
public class NothingToUndoException extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    public NothingToUndoException(String message) {
        super(message);
    }
}

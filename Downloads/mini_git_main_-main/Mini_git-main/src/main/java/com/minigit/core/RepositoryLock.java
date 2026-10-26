package com.minigit.core;

/**
 * Process-wide lock used to serialise operations that modify repository
 * state (index, refs, working-tree checkouts).
 *
 * Tomcat handles every HTTP request on its own thread, so two requests
 * (for example "commit" and "checkout") could otherwise interleave and
 * leave the repository in an inconsistent state.
 */
public final class RepositoryLock {

    public static final Object LOCK = new Object();

    private RepositoryLock() {
        // Prevent instantiation.
    }
}

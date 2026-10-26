package com.minigit.database;

import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import jakarta.servlet.annotation.WebListener;

@WebListener
public final class DatabaseLifecycleListener
        implements ServletContextListener {

    @Override
    public void contextInitialized(ServletContextEvent event) {
        DatabaseSessionFactory database =
                DatabaseSessionFactory.initializeShared();
        event.getServletContext().log(
                "MiniGit H2 database initialized at "
                        + database.databasePath()
        );
    }

    @Override
    public void contextDestroyed(ServletContextEvent event) {
        DatabaseSessionFactory.closeShared();
    }
}

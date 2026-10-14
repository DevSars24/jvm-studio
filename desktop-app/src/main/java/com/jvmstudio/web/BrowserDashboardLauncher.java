package com.jvmstudio.web;

import com.jvmstudio.core.AppContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Standalone launcher for running the JVM Studio Live Browser Dashboard.
 * Serves the full interactive application on http://localhost:8080.
 */
public class BrowserDashboardLauncher {
    private static final Logger logger = LoggerFactory.getLogger(BrowserDashboardLauncher.class);

    public static void main(String[] args) {
        int port = 8080;
        try {
            logger.info("Initializing JVM Studio AppContext for Browser Dashboard...");
            AppContext context = new AppContext();

            WebDashboardServer webServer = new WebDashboardServer(context, port);
            webServer.start();

            System.out.println("==================================================================");
            System.out.println("  JVM STUDIO — LIVE BROWSER DASHBOARD IS RUNNING!");
            System.out.println("  Open your browser and navigate to: http://localhost:" + port);
            System.out.println("==================================================================");

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                logger.info("Shutting down Browser Dashboard...");
                webServer.close();
                context.close();
            }));

            // Keep process alive
            Thread.currentThread().join();
        } catch (Exception e) {
            logger.error("Failed to launch Browser Dashboard on port {}", port, e);
            System.exit(1);
        }
    }
}

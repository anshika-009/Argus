package org.tool;

import javafx.application.Application;

/**
 * Entry point. It deliberately does not extend {@link Application}: starting a plain class that then launches
 * {@link ArgusApp} works from IntelliJ's green Run button and from `mvn javafx:run` alike (a main method inside the
 * Application subclass itself fails in an IDE with "JavaFX runtime components are missing").
 */
public class Main {
    public static void main(String[] args) {
        System.setProperty("java.awt.headless", "false");
        Application.launch(ArgusApp.class, args);
    }
}

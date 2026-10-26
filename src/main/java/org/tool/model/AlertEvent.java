package org.tool.model;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/** One line of the event log: an alert that fired, or a process that was terminated. */
public record AlertEvent(String timestamp, String message) {

    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** An event stamped with the current time. */
    public static AlertEvent now(String message) {
        return new AlertEvent(LocalDateTime.now().format(FORMAT), message);
    }

    /** How the event is shown in the "Recent triggers" list. */
    public String display() {
        return timestamp + "   " + message;
    }
}

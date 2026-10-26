package org.tool.model;

/**
 * One immutable reading of the machine, built by the sampler thread once per second.
 * A percentage of -1 means "not available" and is shown as N/A (for example, no battery).
 *
 * @param timestamp when the reading was taken (epoch milliseconds)
 */
public record SystemSnapshot(double cpu, double memory, double disk, double battery, long timestamp,
                             String memText, String diskText, String batText, long uptimeSecs, long memFreeBytes) {

    /** Value of one metric by name ("CPU", "MEMORY", "DISK" or "BATTERY"); -1 if unknown or unavailable. */
    public double value(String metric) {
        return switch (metric) {
            case "CPU" -> cpu;
            case "MEMORY" -> memory;
            case "DISK" -> disk;
            case "BATTERY" -> battery;
            default -> -1;
        };
    }

    /** Early-warning level: a value is getting high (or a battery is getting low) but no alert has fired yet. */
    public boolean isElevated(String metric) {
        double v = value(metric);
        return v >= 0 && (metric.equals("BATTERY") ? v <= 30 : v >= 70);
    }

    /** "94%", or "N/A" when the value is not available. */
    public static String percentText(double v) {
        return v < 0 ? "N/A" : String.format("%.0f%%", v);
    }
}

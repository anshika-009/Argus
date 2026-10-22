package org.tool;

/** One alert rule: fire when METRIC op threshold has held for `seconds` in a row. */
public record Rule(int id, String metric, String op, double threshold, int seconds, boolean enabled) {
}
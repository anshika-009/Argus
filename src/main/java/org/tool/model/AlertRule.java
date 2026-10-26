package org.tool.model;

/** One alert rule: fire when METRIC op threshold has held for `seconds` in a row. An id of 0 means "not stored yet". */
public record AlertRule(int id, String metric, String op, double threshold, int seconds, boolean enabled) {
}

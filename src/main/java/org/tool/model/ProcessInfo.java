package org.tool.model;

/**
 * One running process.
 *
 * @param cpu       share of total CPU, 0-100 (normalised across all cores, like Task Manager)
 * @param memory    resident memory in bytes
 * @param startTime process creation time (epoch ms); used to detect recycled PIDs before terminating
 */
public record ProcessInfo(int pid, String name, double cpu, long memory, long startTime) {
}

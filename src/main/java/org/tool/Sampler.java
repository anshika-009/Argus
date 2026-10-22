package org.tool;

import oshi.SystemInfo;
import oshi.hardware.CentralProcessor;
import oshi.hardware.GlobalMemory;
import oshi.hardware.PowerSource;
import oshi.software.os.OSProcess;
import oshi.software.os.OperatingSystem;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Reads system metrics through OSHI. Call only from ONE thread (it keeps previous-tick state). */
public class Sampler {

    /** battery is -1 when the machine has no battery. */
    public record Snapshot(double cpu, double mem, double disk, double battery,
                           String memText, String diskText, String batText) {}

    public record Proc(int pid, String name, double cpu, long memBytes) {}

    private final SystemInfo si = new SystemInfo();
    private final CentralProcessor cpu = si.getHardware().getProcessor();
    private final GlobalMemory mem = si.getHardware().getMemory();
    private final OperatingSystem os = si.getOperatingSystem();
    private final int cores = cpu.getLogicalProcessorCount();
    private final File root = pickRoot();

    private long[] prevTicks = cpu.getSystemCpuLoadTicks();
    private Map<Integer, OSProcess> prevProcs = new HashMap<>();

    private static File pickRoot() {
        File[] roots = File.listRoots();
        for (File f : roots) {
            if (f.getPath().toUpperCase().startsWith("C:")) return f;
        }
        return roots[0];
    }

    public Snapshot sample() {
        double c = cpu.getSystemCpuLoadBetweenTicks(prevTicks) * 100;
        prevTicks = cpu.getSystemCpuLoadTicks();

        long total = mem.getTotal();
        long used = total - mem.getAvailable();
        double m = total == 0 ? 0 : used * 100.0 / total;
        String memText = String.format("%.1f / %.1f GB", used / 1e9, total / 1e9);

        long dTotal = root.getTotalSpace();
        long dFree = root.getUsableSpace();
        double d = dTotal == 0 ? 0 : (dTotal - dFree) * 100.0 / dTotal;
        String diskText = String.format("%s  %.0f GB free of %.0f GB", root.getPath(), dFree / 1e9, dTotal / 1e9);

        double bat = -1;
        String batText = "no battery detected";
        List<PowerSource> sources = si.getHardware().getPowerSources();
        if (!sources.isEmpty()) {
            PowerSource p = sources.get(0);
            double pct = p.getRemainingCapacityPercent() * 100;
            if (pct >= 0) {
                bat = pct;
                if (p.isCharging()) {
                    batText = "charging";
                } else {
                    batText = "on battery";
                    long secs = (long) p.getTimeRemainingEstimated();
                    if (secs > 0) batText += String.format(" \u00b7 %dh %02dm left", secs / 3600, (secs % 3600) / 60);
                }
            }
        }
        return new Snapshot(c, m, d, bat, memText, diskText, batText);
    }

    /** All running processes with CPU % (normalised to 0-100 across all cores, like Task Manager). */
    public List<Proc> processes() {
        List<OSProcess> list = os.getProcesses(null, null, 0);
        Map<Integer, OSProcess> current = new HashMap<>();
        List<Proc> out = new ArrayList<>();
        for (OSProcess p : list) {
            current.put(p.getProcessID(), p);
            OSProcess prev = prevProcs.get(p.getProcessID());
            double load = prev == null ? 0 : p.getProcessCpuLoadBetweenTicks(prev) * 100 / cores;
            out.add(new Proc(p.getProcessID(), p.getName(), load, p.getResidentSetSize()));
        }
        prevProcs = current;
        return out;
    }
}
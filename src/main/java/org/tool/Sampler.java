package org.tool;

import com.sun.jna.Native;
import com.sun.jna.Structure;
import com.sun.jna.win32.StdCallLibrary;
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
                           String memText, String diskText, String batText, long uptimeSecs) {}

    /** startMillis = process creation time (epoch ms); used to detect recycled PIDs. */
    public record Proc(int pid, String name, double cpu, long memBytes, long startMillis) {}

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

    /**
     * Each sensor has its own exception boundary: if one fails (e.g. no battery driver),
     * its value becomes -1 ("N/A") and the other sensors are still read normally.
     */
    public Snapshot sample() {
        double c = -1;
        try {
            c = cpu.getSystemCpuLoadBetweenTicks(prevTicks) * 100;
            prevTicks = cpu.getSystemCpuLoadTicks();
        } catch (Exception e) {
            log("CPU", e);
        }

        double m = -1;
        String memText = "N/A";
        try {
            long total = mem.getTotal();
            long used = total - mem.getAvailable();
            m = total == 0 ? 0 : used * 100.0 / total;
            memText = String.format("%.1f / %.1f GB", used / 1e9, total / 1e9);
        } catch (Exception e) {
            log("MEMORY", e);
        }

        double d = -1;
        String diskText = "N/A";
        try {
            long dTotal = root.getTotalSpace();
            long dFree = root.getUsableSpace();
            d = dTotal == 0 ? 0 : (dTotal - dFree) * 100.0 / dTotal;
            diskText = String.format("%s  %.0f GB free of %.0f GB", root.getPath(), dFree / 1e9, dTotal / 1e9);
        } catch (Exception e) {
            log("DISK", e);
        }

        double bat = -1;
        String batText = "no battery detected";
        try {
            String[] r = WINDOWS ? windowsBattery() : oshiBattery();
            if (r != null) {
                bat = Double.parseDouble(r[0]);
                batText = r[1];
            }
        } catch (Exception e) {
            log("BATTERY", e);
            batText = "N/A";
        }
        long uptime = 0;
        try {
            uptime = os.getSystemUptime();
        } catch (Exception e) {
            log("UPTIME", e);
        }
        return new Snapshot(c, m, d, bat, memText, diskText, batText, uptime);
    }


    private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase().startsWith("windows");

    /** Windows SYSTEM_POWER_STATUS structure (declared here so we don't depend on a specific JNA release). */
    @Structure.FieldOrder({"ACLineStatus", "BatteryFlag", "BatteryLifePercent", "SystemStatusFlag",
            "BatteryLifeTime", "BatteryFullLifeTime"})
    public static class PowerStatus extends Structure {
        public byte ACLineStatus;
        public byte BatteryFlag;
        public byte BatteryLifePercent;
        public byte SystemStatusFlag;
        public int BatteryLifeTime;
        public int BatteryFullLifeTime;
    }

    private interface PowerApi extends StdCallLibrary {
        PowerApi INSTANCE = Native.load("kernel32", PowerApi.class);

        boolean GetSystemPowerStatus(PowerStatus out);
    }

    /** Windows: ask the OS directly (same source as the taskbar icon). Returns {percent, text} or null if no battery. */
    private static String[] windowsBattery() {
        PowerStatus st = new PowerStatus();
        if (!PowerApi.INSTANCE.GetSystemPowerStatus(st)) return null;
        int pct = st.BatteryLifePercent & 0xFF;
        boolean noBattery = (st.BatteryFlag & 128) != 0;
        if (noBattery || pct > 100) return null;           // 255 = unknown
        String text;
        if (st.ACLineStatus == 1) {
            text = (st.BatteryFlag & 8) != 0 ? "charging" : "plugged in";
        } else {
            text = "on battery";
            if (st.BatteryLifeTime > 0) {
                text += String.format(" \u00b7 %dh %02dm left", st.BatteryLifeTime / 3600, (st.BatteryLifeTime % 3600) / 60);
            }
        }
        return new String[]{String.valueOf(pct), text};
    }

    /** Other operating systems: fall back to OSHI. */
    private String[] oshiBattery() {
        List<PowerSource> sources = si.getHardware().getPowerSources();
        if (sources.isEmpty()) return null;
        PowerSource p = sources.get(0);
        double pct = p.getRemainingCapacityPercent() * 100;
        if (pct < 0) return null;
        String text = "charging";
        if (!p.isCharging()) {
            text = "on battery";
            long secs = (long) p.getTimeRemainingEstimated();
            if (secs > 0) text += String.format(" \u00b7 %dh %02dm left", secs / 3600, (secs % 3600) / 60);
        }
        return new String[]{String.valueOf(pct), text};
    }

    /** One line describing the machine, e.g. "AMD Ryzen 5 7520U - 8 threads - 15.3 GB RAM". */
    public String hardwareSummary() {
        try {
            return String.format("%s \u00b7 %d threads \u00b7 %.1f GB RAM",
                    cpu.getProcessorIdentifier().getName().trim(), cores, mem.getTotal() / 1e9);
        } catch (Exception e) {
            return "Argus";
        }
    }

    private final java.util.Set<String> reported = new java.util.HashSet<>();

    /** Print each sensor's failure once, not every second. */
    private void log(String sensor, Exception e) {
        if (reported.add(sensor)) {
            System.err.println("Sensor " + sensor + " unavailable: " + e);
        }
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
            out.add(new Proc(p.getProcessID(), p.getName(), load, p.getResidentSetSize(), p.getStartTime()));
        }
        prevProcs = current;
        return out;
    }
}
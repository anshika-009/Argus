package org.tool.sampler;

import com.sun.jna.Native;
import com.sun.jna.Structure;
import com.sun.jna.win32.StdCallLibrary;
import org.tool.alert.AlertEngine;
import org.tool.model.SystemSnapshot;
import oshi.SystemInfo;
import oshi.hardware.CentralProcessor;
import oshi.hardware.GlobalMemory;
import oshi.hardware.PowerSource;
import oshi.software.os.OperatingSystem;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The sampler thread. Once per second it reads CPU, memory, disk and battery through OSHI, builds an immutable
 * {@link SystemSnapshot}, appends it to the ring-buffer history and hands it to the {@link AlertEngine}.
 *
 * <p>The thread runs at {@link Thread#MIN_PRIORITY} so it never competes with the UI under heavy load. The UI never
 * calls the OS: it asks {@link #dispatchLatest()} once per frame, which delivers only the NEWEST snapshot to the
 * listeners, so if rendering falls behind, older snapshots are simply skipped (frame dropping).
 */
public class MetricsSampler {

    public static final String[] METRICS = {"CPU", "MEMORY", "DISK", "BATTERY"};
    public static final int HISTORY = 60;                    // samples kept per metric (seconds of graph history)

    /** Receives each new snapshot on the thread that calls {@link #dispatchLatest()} (the JavaFX thread). */
    public interface SnapshotListener {
        void onSnapshot(SystemSnapshot snapshot);
    }

    private final AlertEngine alertEngine;
    private final SystemInfo si = new SystemInfo();
    private final CentralProcessor cpu = si.getHardware().getProcessor();
    private final GlobalMemory mem = si.getHardware().getMemory();
    private final OperatingSystem os = si.getOperatingSystem();
    private final int cores = cpu.getLogicalProcessorCount();
    private final File root = pickRoot();
    private long[] prevTicks = cpu.getSystemCpuLoadTicks();

    private final Map<String, RingBuffer> history = new LinkedHashMap<>();   // filled once, never modified afterwards
    private final AtomicReference<SystemSnapshot> latest = new AtomicReference<>();
    private final List<SnapshotListener> listeners = new CopyOnWriteArrayList<>();
    private SystemSnapshot lastDispatched;                    // touched only by the dispatching (UI) thread
    private ScheduledExecutorService scheduler;

    public MetricsSampler(AlertEngine alertEngine) {
        this.alertEngine = alertEngine;
        for (String m : METRICS) history.put(m, new RingBuffer(HISTORY));
    }

    // ---------------------------------------------------------------- lifecycle

    public void addListener(SnapshotListener listener) {
        listeners.add(listener);
    }

    public synchronized void start() {
        if (scheduler != null) return;
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "argus-sampler");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);               // never compete with the UI under heavy load
            return t;
        });
        scheduler.scheduleAtFixedRate(this::tick, 0, 1, TimeUnit.SECONDS);
    }

    public synchronized void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
    }

    private void tick() {
        try {                                                 // an exception would silently cancel the schedule
            SystemSnapshot s = sample();
            alertEngine.evaluate(s);
            for (String m : METRICS) history.get(m).add(Math.max(s.value(m), 0));
            latest.set(s);                                    // publish; the UI picks up the newest one
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // ---------------------------------------------------------------- reading the UI side uses

    /** The ring buffer holding the last {@link #HISTORY} samples of one metric. */
    public RingBuffer history(String metric) {
        return history.get(metric);
    }

    public SystemSnapshot latest() {
        return latest.get();
    }

    /**
     * Call once per UI frame. Delivers the newest snapshot to every listener, but only if it has not been
     * delivered before; snapshots that arrived in between are dropped.
     */
    public void dispatchLatest() {
        SystemSnapshot s = latest.get();
        if (s != null && s != lastDispatched) {
            lastDispatched = s;
            for (SnapshotListener l : listeners) l.onSnapshot(s);
        }
    }

    /** CPU model and thread count only, e.g. "AMD Ryzen 5 7520U \u00b7 8 threads". */
    public String cpuSummary() {
        try {
            String name = cpu.getProcessorIdentifier().getName().trim()
                    .replaceAll("(?i)\\s+with\\s+.*$", "")          // drop "with Radeon Graphics"
                    .replaceAll("(?i)\\((R|TM)\\)", "")
                    .replaceAll("\\s+", " ");
            return String.format("%s \u00b7 %d threads", name, cores);
        } catch (Exception e) {
            return "Processor";
        }
    }

    // ---------------------------------------------------------------- sensors

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
    private SystemSnapshot sample() {
        double c = -1;
        try {
            c = cpu.getSystemCpuLoadBetweenTicks(prevTicks) * 100;
            prevTicks = cpu.getSystemCpuLoadTicks();
        } catch (Exception e) {
            log("CPU", e);
        }

        double m = -1;
        String memText = "N/A";
        long memFree = -1;
        try {
            long total = mem.getTotal();
            long used = total - mem.getAvailable();
            memFree = total - used;
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
            String[] r = batteryReading();
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
        return new SystemSnapshot(c, m, d, bat, System.currentTimeMillis(), memText, diskText, batText, uptime, memFree);
    }

    private static final String OS_NAME = System.getProperty("os.name", "").toLowerCase();
    private static final boolean WINDOWS = OS_NAME.startsWith("windows");
    private static final boolean MAC = OS_NAME.startsWith("mac");
    private static final boolean LINUX = OS_NAME.startsWith("linux");

    /** One battery reading as {percent, text}, or null if there is no battery. Each OS asks its own source. */
    private String[] batteryReading() throws Exception {
        if (WINDOWS) return windowsBattery();
        if (MAC || LINUX) {
            try {
                return MAC ? macBattery() : linuxBattery();
            } catch (Exception e) {
                log("BATTERY (native)", e);                // the native source failed: fall back to OSHI
                return oshiBattery();
            }
        }
        return oshiBattery();
    }

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

    // ---------------------------------------------------------------- Linux

    /** Linux: reads /sys/class/power_supply. Returns null when the machine has no battery (e.g. a desktop PC). */
    private static String[] linuxBattery() throws IOException {
        Path base = Path.of("/sys/class/power_supply");
        if (!Files.isDirectory(base)) return null;
        Path bat;
        try (Stream<Path> dirs = Files.list(base)) {
            bat = dirs.sorted()
                    .filter(d -> "Battery".equalsIgnoreCase(sysfs(d, "type")))
                    .filter(d -> !"Device".equalsIgnoreCase(sysfs(d, "scope")))     // skip mouse / headset batteries
                    .findFirst().orElse(null);
        }
        if (bat == null) return null;

        double energyNow = sysfsNumber(bat, "energy_now");          // micro-watt-hours, or...
        double energyFull = sysfsNumber(bat, "energy_full");
        double rate = sysfsNumber(bat, "power_now");                // micro-watts
        if (energyNow < 0 || energyFull <= 0) {                     // ...micro-amp-hours on some machines
            energyNow = sysfsNumber(bat, "charge_now");
            energyFull = sysfsNumber(bat, "charge_full");
            rate = sysfsNumber(bat, "current_now");
        }

        double pct = sysfsNumber(bat, "capacity");
        if (pct < 0 && energyNow >= 0 && energyFull > 0) pct = energyNow * 100.0 / energyFull;
        if (pct < 0) return null;
        pct = Math.min(pct, 100);

        String status = String.valueOf(sysfs(bat, "status")).toLowerCase();
        String text;
        if (status.startsWith("charging")) {
            text = "charging";
        } else if (status.startsWith("discharging")) {
            text = "on battery";
            if (rate > 0 && energyNow > 0) {
                long secs = (long) (energyNow / rate * 3600);
                text += String.format(" \u00b7 %dh %02dm left", secs / 3600, (secs % 3600) / 60);
            }
        } else {
            text = "plugged in";                                    // "Full", "Not charging" or unknown
        }
        return new String[]{String.valueOf(Math.round(pct)), text};
    }

    /** One sysfs file as trimmed text, or null if it does not exist or cannot be read. */
    private static String sysfs(Path dir, String file) {
        try {
            return Files.readString(dir.resolve(file)).trim();
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** One sysfs file as a number, or -1 if it is missing or not a number. */
    private static double sysfsNumber(Path dir, String file) {
        String v = sysfs(dir, file);
        if (v == null) return -1;
        try {
            return Double.parseDouble(v);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    // ---------------------------------------------------------------- macOS

    private static final Pattern MAC_LEVEL = Pattern.compile("(\\d+)%;\\s*([^;\\n]+)");
    private static final Pattern MAC_TIME = Pattern.compile("(\\d+):(\\d{2}) remaining");
    private long macCheckedAt;                                      // used by the sampler thread only
    private String[] macCached;

    /** macOS: parses `pmset -g batt`. Asked at most every 5 seconds, because it starts a process each time. */
    private String[] macBattery() throws Exception {
        long now = System.currentTimeMillis();
        if (macCheckedAt != 0 && now - macCheckedAt < 5000) return macCached;
        macCheckedAt = now;

        Process proc = new ProcessBuilder("pmset", "-g", "batt").redirectErrorStream(true).start();
        if (!proc.waitFor(3, TimeUnit.SECONDS)) {
            proc.destroyForcibly();
            throw new IOException("pmset did not answer in time");
        }
        String out = new String(proc.getInputStream().readAllBytes());

        Matcher level = MAC_LEVEL.matcher(out);
        if (!level.find()) {                                        // no "NN%" line: a Mac without a battery
            macCached = null;
            return null;
        }
        String pct = level.group(1);
        String state = level.group(2).trim().toLowerCase();
        String text;
        if (state.startsWith("discharging")) {
            text = "on battery";
            Matcher t = MAC_TIME.matcher(out);
            if (t.find()) {
                int h = Integer.parseInt(t.group(1));
                int m = Integer.parseInt(t.group(2));
                if (h > 0 || m > 0) text += String.format(" \u00b7 %dh %02dm left", h, m);
            }
        } else if (state.startsWith("charging") || state.startsWith("finishing charge")) {
            text = "charging";
        } else {
            text = "plugged in";                                    // "charged", "AC attached", "not charging"
        }
        macCached = new String[]{pct, text};
        return macCached;
    }

    /** Fallback for any other situation: OSHI. */
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

    private final Set<String> reported = new HashSet<>();

    /** Print each sensor's failure once, not every second. */
    private void log(String sensor, Exception e) {
        if (reported.add(sensor)) {
            System.err.println("Sensor " + sensor + " unavailable: " + e);
        }
    }
}
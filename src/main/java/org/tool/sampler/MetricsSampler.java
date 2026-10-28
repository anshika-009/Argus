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

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
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
    private final FileStore diskStore = pickStore();
    private final String diskLabel = diskLabel();
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

    /**
     * The volume that holds the user's home folder: the disk the user actually fills up. On Windows that is the
     * profile drive, on macOS the Data volume, on Linux the partition behind /home (which may not be "/").
     * Falls back to the first filesystem root if the home folder cannot be resolved.
     */
    private static FileStore pickStore() {
        try {
            Path home = Path.of(System.getProperty("user.home", ""));
            if (Files.exists(home)) return Files.getFileStore(home);
        } catch (Exception ignored) {
            // fall through to the roots
        }
        try {
            for (Path r : java.nio.file.FileSystems.getDefault().getRootDirectories()) return Files.getFileStore(r);
        } catch (Exception ignored) {
            // no usable store: the DISK sensor will report N/A
        }
        return null;
    }

    /** "C:" on Windows; "Home" elsewhere (the volume is chosen by home folder, so a mount path would mislead). */
    private static String diskLabel() {
        if (WINDOWS) {
            try {
                Path root = Path.of(System.getProperty("user.home", "")).getRoot();
                if (root != null) return root.toString().replace("\\", "");
            } catch (Exception ignored) {
                // use the generic label
            }
        }
        return "Home";
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
            if (diskStore == null) throw new IOException("no file store for the home folder");
            long dTotal = diskStore.getTotalSpace();
            long dFreeAll = diskStore.getUnallocatedSpace();    // f_bfree: includes blocks reserved for root
            long dAvail = diskStore.getUsableSpace();           // f_bavail: what a normal user can still write
            long dUsed = dTotal - dFreeAll;
            long dDenom = dUsed + dAvail;                       // same formula as `df`: reserved blocks are not "used"
            d = dDenom <= 0 ? 0 : dUsed * 100.0 / dDenom;
            diskText = String.format("%s  %.0f GB free of %.0f GB", diskLabel, dAvail / 1e9, dTotal / 1e9);
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

    private final Deque<Double> linuxRates = new ArrayDeque<>();   // recent discharge rates, sampler thread only
    private static final int RATE_WINDOW = 30;                     // seconds averaged for the time-left estimate

    /**
     * Linux: reads /sys/class/power_supply. Returns null when the machine has no battery (e.g. a desktop PC).
     * All internal batteries are added together (some laptops have two), mains/USB-C adapters decide "plugged in"
     * when the battery status is vague, and the time-left estimate uses a 30 s average because power_now jumps.
     */
    private String[] linuxBattery() throws IOException {
        Path base = Path.of("/sys/class/power_supply");
        if (!Files.isDirectory(base)) return null;

        List<Path> bats = new ArrayList<>();
        boolean haveAcInfo = false, acOnline = false;
        try (Stream<Path> dirs = Files.list(base)) {
            for (Path d : dirs.sorted().toList()) {
                String type = sysfs(d, "type");
                if (type == null || "Device".equalsIgnoreCase(sysfs(d, "scope"))) continue;   // skip mouse / headset
                if ("Battery".equalsIgnoreCase(type)) {
                    if (!"0".equals(sysfs(d, "present"))) bats.add(d);                      // 0 = empty bay
                } else if (!"UPS".equalsIgnoreCase(type)) {                                   // Mains, USB, USB_C, ...
                    String online = sysfs(d, "online");
                    if (online != null) {
                        haveAcInfo = true;
                        if (online.equals("1")) acOnline = true;
                    }
                }
            }
        }
        if (bats.isEmpty()) return null;

        double now = 0, full = 0, rate = 0, capSum = 0;
        int capCount = 0;
        boolean unitsOk = true;
        String unit = null;
        boolean charging = false, discharging = false;
        for (Path bat : bats) {
            String u = "energy";                                    // micro-watt-hours / micro-watts, or...
            double n = sysfsNumber(bat, "energy_now");
            double f = sysfsNumber(bat, "energy_full");
            double r = sysfsNumber(bat, "power_now");
            if (n < 0 || f <= 0) {                                  // ...micro-amp-hours / micro-amps on some machines
                u = "charge";
                n = sysfsNumber(bat, "charge_now");
                f = sysfsNumber(bat, "charge_full");
                r = sysfsNumber(bat, "current_now");
            }
            if (n >= 0 && f > 0) {
                if (unit == null) unit = u;
                else if (!unit.equals(u)) unitsOk = false;          // can't add Wh to Ah
                now += n;
                full += f;
                if (r > 0) rate += r;
            } else {
                unitsOk = false;
            }
            double c = sysfsNumber(bat, "capacity");
            if (c >= 0) {
                capSum += c;
                capCount++;
            }
            String st = String.valueOf(sysfs(bat, "status")).toLowerCase();
            if (st.startsWith("charging")) charging = true;
            else if (st.startsWith("discharging")) discharging = true;
        }

        double pct;
        if (bats.size() == 1 && capCount == 1) pct = capSum;       // the kernel's own number, like the desktop shows
        else if (unitsOk && full > 0) pct = now * 100.0 / full;
        else if (capCount > 0) pct = capSum / capCount;
        else return null;
        pct = Math.min(Math.max(pct, 0), 100);

        boolean onBattery = !charging && (discharging || (haveAcInfo && !acOnline));
        String text;
        if (charging) {
            text = "charging";
        } else if (onBattery) {
            text = "on battery";
            if (unitsOk && rate > 0 && now > 0) {
                linuxRates.addLast(rate);
                if (linuxRates.size() > RATE_WINDOW) linuxRates.removeFirst();
                double avg = linuxRates.stream().mapToDouble(Double::doubleValue).average().orElse(rate);
                long secs = (long) (now / avg * 3600);
                text += String.format(" \u00b7 %dh %02dm left", secs / 3600, (secs % 3600) / 60);
            }
        } else {
            text = "plugged in";                                    // "Full", "Not charging" (charge limit), AC online
        }
        if (!onBattery) linuxRates.clear();
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
    private long macCheckedAt;                                      // last successful read; sampler thread only
    private long macFailedAt;                                       // last failed read; 0 = none
    private String[] macCached;

    /**
     * macOS: parses `pmset -g batt`. Asked at most every 5 seconds, because it starts a process each time.
     * A failed run is not cached as an answer: the caller falls back to OSHI, and pmset is retried after 30 s.
     */
    private String[] macBattery() throws Exception {
        long now = System.currentTimeMillis();
        if (macCheckedAt != 0 && now - macCheckedAt < 5000) return macCached;
        if (macFailedAt != 0 && now - macFailedAt < 30_000) throw new IOException("pmset failed recently, using OSHI");

        String out;
        Process proc = null;
        try {
            proc = new ProcessBuilder("/usr/bin/pmset", "-g", "batt").redirectErrorStream(true).start();
            InputStream in = proc.getInputStream();
            out = CompletableFuture.supplyAsync(() -> {             // read while waiting, so a full pipe cannot block it
                try {
                    return new String(in.readAllBytes(), StandardCharsets.UTF_8);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }).get(3, TimeUnit.SECONDS);
        } catch (Exception e) {
            macFailedAt = now;
            throw e;
        } finally {
            if (proc != null && proc.isAlive()) proc.destroyForcibly();
        }
        macFailedAt = 0;
        macCheckedAt = now;

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
package org.tool.process;

import org.tool.model.ProcessInfo;
import oshi.SystemInfo;
import oshi.software.os.OSProcess;
import oshi.software.os.OperatingSystem;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Keeps an up-to-date list of running processes. A background thread (minimum priority, like the sampler) reads the
 * process table every 2 seconds; the UI only ever reads the cached result through {@link #topBy}.
 */
public class ProcessMonitor {

    private final SystemInfo si = new SystemInfo();
    private final OperatingSystem os = si.getOperatingSystem();
    private final int cores = si.getHardware().getProcessor().getLogicalProcessorCount();
    private Map<Integer, OSProcess> previous = new HashMap<>();      // touched only by the monitor thread

    private final AtomicReference<List<ProcessInfo>> latest = new AtomicReference<>(List.of());
    private volatile long version;                                   // increases after every refresh
    private ScheduledExecutorService scheduler;

    public synchronized void start() {
        if (scheduler != null) return;
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "argus-processes");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });
        scheduler.scheduleAtFixedRate(this::refresh, 0, 2, TimeUnit.SECONDS);
    }

    public synchronized void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
    }

    /** Changes every time a new process list is available; the UI compares it to know when to redraw. */
    public long version() {
        return version;
    }

    /**
     * The n most resource-hungry processes, biggest first.
     *
     * @param metric "CPU" or "MEMORY"
     * @param n      how many to return; pass Integer.MAX_VALUE for the whole ranked list
     */
    public List<ProcessInfo> topBy(String metric, int n) {
        Comparator<ProcessInfo> ranking = "MEMORY".equalsIgnoreCase(metric)
                ? Comparator.comparingLong(ProcessInfo::memory)
                : Comparator.comparingDouble(ProcessInfo::cpu);
        return latest.get().stream().sorted(ranking.reversed()).limit(n).toList();
    }

    /** Reads every running process, with CPU % normalised to 0-100 across all cores (like Task Manager). */
    private void refresh() {
        try {                                   // a process-read failure must not cancel the schedule
            List<OSProcess> list = os.getProcesses(null, null, 0);
            Map<Integer, OSProcess> current = new HashMap<>();
            List<ProcessInfo> out = new ArrayList<>();
            for (OSProcess p : list) {
                current.put(p.getProcessID(), p);
                OSProcess prev = previous.get(p.getProcessID());
                double load = prev == null ? 0 : p.getProcessCpuLoadBetweenTicks(prev) * 100 / cores;
                out.add(new ProcessInfo(p.getProcessID(), p.getName(), load, p.getResidentSetSize(), p.getStartTime()));
            }
            previous = current;
            latest.set(List.copyOf(out));
            version++;
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}

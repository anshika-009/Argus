package org.tool;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;

public final class ProcessGuard {

    /** allowed = false means "do not terminate"; reason is shown to the user. */
    public record Verdict(boolean allowed, String reason, ProcessHandle handle) {
        static Verdict deny(String reason) {
            return new Verdict(false, reason, null);
        }
    }

    private static final Set<String> CRITICAL = Set.of(
            // Windows
            "system", "system idle process", "registry", "memory compression", "secure system",
            "smss.exe", "csrss.exe", "wininit.exe", "winlogon.exe", "services.exe",
            "lsass.exe", "lsaiso.exe", "svchost.exe", "dwm.exe", "fontdrvhost.exe",
            // Linux / macOS
            "init", "systemd", "launchd", "kernel_task", "kthreadd");

    /** OSHI and Java read the same creation time but round differently. */
    private static final long START_TOLERANCE_MS = 2000;

    private ProcessGuard() {
    }

    public static Verdict check(Sampler.Proc p) {
        if (p.pid() <= 4 || p.pid() == ProcessHandle.current().pid()) {
            return Verdict.deny(p.name() + " is protected and cannot be terminated.");
        }
        if (CRITICAL.contains(p.name().toLowerCase())) {
            return Verdict.deny(p.name() + " is a critical system process. Terminating it could crash or log you out of Windows.");
        }

        Optional<ProcessHandle> found = ProcessHandle.of(p.pid());
        if (found.isEmpty() || !found.get().isAlive()) {
            return Verdict.deny(p.name() + " has already exited.");
        }
        ProcessHandle handle = found.get();
        ProcessHandle.Info info = handle.info();

        Optional<String> user = info.user();
        if (user.isEmpty()) {
            return Verdict.deny("Argus has no permission to inspect " + p.name()
                    + ".\nRun Argus as administrator to manage this process.");
        }
        if (user.get().toUpperCase().startsWith("NT AUTHORITY\\")) {
            return Verdict.deny(p.name() + " runs as a system service account and is protected.");
        }

        Optional<Instant> start = info.startInstant();
        if (start.isEmpty()) {
            return Verdict.deny("Could not verify the start time of " + p.name() + ", so it was not terminated.");
        }
        if (p.startMillis() > 0
                && Math.abs(start.get().toEpochMilli() - p.startMillis()) > START_TOLERANCE_MS) {
            return Verdict.deny("PID " + p.pid() + " now belongs to a different program than the one in the list "
                    + "(the old process exited and the PID was reused). Nothing was terminated.");
        }
        return new Verdict(true, "", handle);
    }
}
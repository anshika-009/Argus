package org.tool.process;

import org.tool.model.ProcessInfo;

import java.util.Optional;
import java.util.Set;

/**
 * Decides which processes must never be terminated: Argus itself, the idle/system PIDs, a list of critical
 * operating-system processes, and anything running under a Windows system account (NT AUTHORITY\...).
 */
public class ProtectedProcessPolicy {

    private static final Set<String> CRITICAL = Set.of(
            // Windows
            "system", "system idle process", "registry", "memory compression", "secure system",
            "smss.exe", "csrss.exe", "wininit.exe", "winlogon.exe", "services.exe",
            "lsass.exe", "lsaiso.exe", "svchost.exe", "dwm.exe", "fontdrvhost.exe",
            // Linux / macOS
            "init", "systemd", "launchd", "kernel_task", "kthreadd");

    public boolean isProtected(ProcessInfo info) {
        return reason(info).isPresent();
    }

    /** Why the process is protected, in words the user can read; empty if it may be terminated. */
    public Optional<String> reason(ProcessInfo info) {
        if (info.pid() <= 4 || info.pid() == ProcessHandle.current().pid()) {
            return Optional.of(info.name() + " is protected and cannot be terminated.");
        }
        if (CRITICAL.contains(info.name().toLowerCase())) {
            return Optional.of(info.name() + " is a critical system process. Terminating it could crash or log you out of Windows.");
        }
        Optional<String> user = ProcessHandle.of(info.pid()).flatMap(h -> h.info().user());
        if (user.isPresent() && user.get().toUpperCase().startsWith("NT AUTHORITY\\")) {
            return Optional.of(info.name() + " runs as a system service account and is protected.");
        }
        return Optional.empty();
    }
}

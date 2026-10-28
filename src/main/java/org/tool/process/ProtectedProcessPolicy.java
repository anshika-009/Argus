package org.tool.process;

import org.tool.model.ProcessInfo;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Decides which processes must never be terminated: Argus itself, the idle/system PIDs, kernel threads, a list of
 * critical operating-system processes (chosen for the OS Argus is running on), and anything running under a
 * Windows system or service account (NT AUTHORITY\..., NT SERVICE\...).
 */
public class ProtectedProcessPolicy {

    private static final String OS_NAME = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
    private static final boolean WINDOWS = OS_NAME.startsWith("windows");
    private static final boolean MAC = OS_NAME.startsWith("mac");

    // All names are lower case; they are compared with Locale.ROOT so a Turkish-language PC still matches "Idle".
    private static final Set<String> WINDOWS_CRITICAL = Set.of(
            "system", "system idle process", "registry", "memory compression", "secure system",
            "smss.exe", "csrss.exe", "wininit.exe", "winlogon.exe", "services.exe",
            "lsass.exe", "lsaiso.exe", "svchost.exe", "dwm.exe", "fontdrvhost.exe");

    private static final Set<String> MAC_CRITICAL = Set.of(
            "launchd", "kernel_task", "windowserver", "loginwindow", "configd", "opendirectoryd");

    private static final Set<String> LINUX_CRITICAL = Set.of(
            "init", "systemd", "kthreadd", "systemd-logind", "dbus-daemon",
            "xorg", "xwayland", "gnome-shell", "kwin_x11", "kwin_wayland", "plasmashell",
            "gdm", "sddm", "lightdm");

    private static final Set<String> CRITICAL = WINDOWS ? WINDOWS_CRITICAL : MAC ? MAC_CRITICAL : LINUX_CRITICAL;

    public boolean isProtected(ProcessInfo info) {
        return reason(info).isPresent();
    }

    /** Why the process is protected, in words the user can read; empty if it may be terminated. */
    public Optional<String> reason(ProcessInfo info) {
        if (info.pid() == ProcessHandle.current().pid()) {
            return Optional.of("Argus cannot terminate itself.");
        }
        if (info.pid() <= 4) {
            return Optional.of(info.name() + " is protected and cannot be terminated.");
        }
        if (CRITICAL.contains(info.name().toLowerCase(Locale.ROOT))) {
            return Optional.of(info.name() + " is a critical system process. Terminating it could crash "
                    + "the system or log you out.");
        }
        if (!WINDOWS && !MAC && isKernelThread(info.pid())) {       // Linux: kernel threads are children of kthreadd (PID 2)
            return Optional.of(info.name() + " is a kernel thread and cannot be terminated.");
        }
        Optional<String> user = ProcessHandle.of(info.pid()).flatMap(h -> h.info().user());
        if (user.isPresent()) {
            String account = user.get().toUpperCase(Locale.ROOT);
            if (account.startsWith("NT AUTHORITY\\") || account.startsWith("NT SERVICE\\")) {
                return Optional.of(info.name() + " runs as a system service account and is protected.");
            }
        }
        return Optional.empty();
    }

    private static boolean isKernelThread(int pid) {
        return ProcessHandle.of(pid)
                .flatMap(ProcessHandle::parent)
                .map(p -> p.pid() == 2L)
                .orElse(false);
    }
}
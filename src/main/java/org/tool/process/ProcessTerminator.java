package org.tool.process;

import org.tool.model.ProcessInfo;

import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Terminates a process, but only after safety checks: the {@link ProtectedProcessPolicy} must allow it, the
 * process must still be alive, Argus must have permission to inspect it, and its start time must still match the one
 * in the list (so a recycled PID, now belonging to a different program, is never killed by mistake).
 *
 * <p>How the kill itself works depends on the OS. Windows has no "ask nicely" signal, so the process is ended at
 * once (TerminateProcess). Linux and macOS first send SIGTERM, which lets a program save its work and exit; if it
 * ignores that for {@link #GRACE_SECONDS} seconds, Argus sends SIGKILL, which cannot be ignored.
 */
public class ProcessTerminator {

    /** allowed = false means "do not terminate"; reason is shown to the user. */
    public record Verdict(boolean allowed, String reason, ProcessHandle handle) {
        static Verdict deny(String reason) {
            return new Verdict(false, reason, null);
        }
    }

    /** Outcome of {@link #terminate}; message explains a failure. */
    public record Result(boolean success, String message) {
    }

    private static final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");

    /**
     * OSHI and Java read the same creation time but round differently. On Linux both derive it from the boot time,
     * which can drift by a few seconds (suspend, clock changes), so the allowed difference is larger there.
     * A wrongly reused PID would have to start within this window of the original, which is practically impossible.
     */
    private static final long START_TOLERANCE_MS = WINDOWS ? 2_000 : 5_000;

    /** Linux/macOS: how long a program gets to shut down after SIGTERM before it is killed with SIGKILL. */
    public static final long GRACE_SECONDS = 5;
    /** How long to wait for the process to disappear after a forced kill (and on Windows). */
    private static final long KILL_WAIT_SECONDS = 3;

    private final ProtectedProcessPolicy policy;

    public ProcessTerminator(ProtectedProcessPolicy policy) {
        this.policy = policy;
    }

    /** Runs every safety check without killing anything. Used before asking the user to confirm. */
    public Verdict check(ProcessInfo info) {
        Optional<String> protectedReason = policy.reason(info);
        if (protectedReason.isPresent()) return Verdict.deny(protectedReason.get());

        return verifyIdentity(info);
    }

    /**
     * Re-checks, then terminates the process the gentle way where the OS has one (SIGTERM, then SIGKILL after a
     * grace period) and immediately on Windows. This blocks for up to about 8 seconds, so call it from a
     * background thread, never the UI thread.
     */
    public Result terminate(ProcessInfo info) {
        return terminate(info, false);
    }

    /**
     * Same as {@link #terminate(ProcessInfo)}, but with {@code forceNow} the process is killed immediately
     * (SIGKILL) without a grace period. Meant for a "Force kill" button.
     */
    public Result terminate(ProcessInfo info, boolean forceNow) {
        Verdict verdict = check(info);
        if (!verdict.allowed()) return new Result(false, verdict.reason());
        ProcessHandle handle = verdict.handle();
        try {
            boolean immediate = forceNow || WINDOWS;
            boolean sent = immediate ? handle.destroyForcibly() : handle.destroy();   // SIGKILL / SIGTERM / TerminateProcess
            if (!sent) return failedToSend(handle, info);

            if (waitForExit(handle, immediate ? KILL_WAIT_SECONDS : GRACE_SECONDS)) return new Result(true, "");

            if (!immediate) {                                       // ignored SIGTERM: now it cannot be ignored
                if (!handle.destroyForcibly()) return failedToSend(handle, info);
                if (waitForExit(handle, KILL_WAIT_SECONDS)) return new Result(true, "");
            }
            return new Result(false, info.name() + " did not exit. It may be stuck in the kernel (for example "
                    + "waiting on a disk or network drive); try again in a moment.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(false, "Interrupted while waiting for " + info.name() + " to exit.");
        } catch (Exception e) {
            return new Result(false, "Could not terminate " + info.name() + ": " + e.getMessage());
        }
    }

    /** True when the process is gone within the time limit. */
    private static boolean waitForExit(ProcessHandle handle, long seconds) throws InterruptedException {
        try {
            handle.onExit().get(seconds, TimeUnit.SECONDS);
            return true;
        } catch (TimeoutException e) {
            return !handle.isAlive();
        } catch (ExecutionException e) {
            return !handle.isAlive();
        }
    }

    /** The signal could not be sent: either the process just exited (fine) or the OS said no. */
    private static Result failedToSend(ProcessHandle handle, ProcessInfo info) {
        if (!handle.isAlive()) return new Result(true, "");
        if (WINDOWS) {
            return new Result(false, "Windows refused to terminate " + info.name()
                    + " (access denied).\nTry running Argus as administrator.");
        }
        return new Result(false, "The system refused to stop " + info.name() + " (permission denied).\n"
                + "Run Argus with sudo, or as the user who owns this process.");
    }

    /** The process must be alive, inspectable, and still be the same program that was listed. */
    private Verdict verifyIdentity(ProcessInfo info) {
        Optional<ProcessHandle> found = ProcessHandle.of(info.pid());
        if (found.isEmpty() || !found.get().isAlive()) {
            return Verdict.deny(info.name() + " has already exited.");
        }
        ProcessHandle handle = found.get();
        ProcessHandle.Info details = handle.info();

        if (details.user().isEmpty()) {
            return Verdict.deny("Argus has no permission to inspect " + info.name() + ".\n"
                    + (WINDOWS ? "Run Argus as administrator to manage this process."
                    : "Run Argus with sudo to manage this process."));
        }

        if (!WINDOWS) {
            // Linux/macOS only let you signal your own processes (or any, as root). Say so now, before the user confirms.
            String owner = details.user().get();
            String me = ProcessHandle.current().info().user().orElse(null);
            if (me != null && !me.equals(owner) && !me.equals("root")) {
                return Verdict.deny(info.name() + " belongs to user \"" + owner + "\", but Argus is running as \""
                        + me + "\".\nRun Argus with sudo to manage this process.");
            }
        }

        Optional<Instant> start = details.startInstant();
        if (start.isEmpty()) {
            return Verdict.deny("Could not verify the start time of " + info.name() + ", so it was not terminated.");
        }
        if (info.startTime() > 0
                && Math.abs(start.get().toEpochMilli() - info.startTime()) > START_TOLERANCE_MS) {
            return Verdict.deny("PID " + info.pid() + " now belongs to a different program than the one in the list "
                    + "(the old process exited and the PID was reused). Nothing was terminated.");
        }
        return new Verdict(true, "", handle);
    }
}
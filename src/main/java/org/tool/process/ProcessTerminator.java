package org.tool.process;

import org.tool.model.ProcessInfo;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Terminates a process, but only after safety checks: the {@link ProtectedProcessPolicy} must allow it, the
 * process must still be alive, Argus must have permission to inspect it, and its start time must still match the one
 * in the list (so a recycled PID, now belonging to a different program, is never killed by mistake).
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

    /** OSHI and Java read the same creation time but round differently. */
    private static final long START_TOLERANCE_MS = 2000;

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
     * Re-checks, then terminates the process and waits up to 3 seconds for it to exit.
     * This blocks, so call it from a background thread, never the UI thread.
     */
    public Result terminate(ProcessInfo info) {
        Verdict verdict = check(info);
        if (!verdict.allowed()) return new Result(false, verdict.reason());
        ProcessHandle handle = verdict.handle();
        try {
            if (!handle.destroy()) {
                return new Result(false, "Windows refused to terminate " + info.name()
                        + " (access denied).\nTry running Argus as administrator.");
            }
            handle.onExit().get(3, TimeUnit.SECONDS);
            return new Result(true, "");
        } catch (TimeoutException e) {
            return new Result(false, info.name() + " did not exit within 3 seconds.");
        } catch (Exception e) {
            return new Result(false, "Could not terminate " + info.name() + ": " + e.getMessage());
        }
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
            return Verdict.deny("Argus has no permission to inspect " + info.name()
                    + ".\nRun Argus as administrator to manage this process.");
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

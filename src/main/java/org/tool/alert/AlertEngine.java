package org.tool.alert;

import org.tool.model.AlertEvent;
import org.tool.model.AlertRule;
import org.tool.model.SystemSnapshot;
import org.tool.storage.SettingsRepository;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Applies the user's alert rules to every snapshot. A rule fires only after its limit has been breached for the
 * whole duration in a row (no alert spam from brief spikes). When a rule fires, the engine shows a desktop
 * notification ({@link NotificationService}) and writes the event to the log ({@link SettingsRepository}).
 */
public class AlertEngine {

    private final SettingsRepository repository;
    private final NotificationService notifications;

    private final Map<Integer, Integer> counters = new HashMap<>();   // seconds each rule has been breached in a row
    private List<AlertRule> rules = List.of();
    private volatile Set<String> firing = Set.of();
    private volatile Runnable onEvent;

    public AlertEngine(SettingsRepository repository, NotificationService notifications) {
        this.repository = repository;
        this.notifications = notifications;
    }

    public synchronized void setRules(List<AlertRule> newRules) {
        rules = List.copyOf(newRules);
        counters.clear();
    }

    /** Metrics that are over their limit right now (long enough for a rule to have fired). Safe from any thread. */
    public Set<String> firing() {
        return firing;
    }

    /** Called (on the sampler thread) after a rule fires, so the UI can refresh its event list. */
    public void setOnEvent(Runnable callback) {
        this.onEvent = callback;
    }

    public void evaluate(SystemSnapshot s) {
        Set<String> nowFiring = new HashSet<>();
        List<String> fired = new ArrayList<>();
        synchronized (this) {
            for (AlertRule r : rules) {
                double v = s.value(r.metric());
                if (!r.enabled() || v < 0) {      // disabled rule, or metric not available (no battery)
                    counters.remove(r.id());
                    continue;
                }
                boolean breached = r.op().equals(">") ? v > r.threshold() : v < r.threshold();
                if (breached) {
                    int n = counters.merge(r.id(), 1, Integer::sum);
                    if (n >= r.seconds()) nowFiring.add(r.metric());
                    if (n == r.seconds()) {
                        fired.add(String.format("%s at %.0f%%  (rule: %s %.0f%% for %ds)",
                                r.metric(), v, r.op(), r.threshold(), r.seconds()));
                    }
                } else {
                    counters.remove(r.id());
                }
            }
            firing = nowFiring;
        }

        for (String message : fired) {            // side effects happen outside the lock
            repository.saveEvent(AlertEvent.now(message));
            notifications.notify("Argus alert", message);
        }
        Runnable callback = onEvent;
        if (!fired.isEmpty() && callback != null) callback.run();
    }
}

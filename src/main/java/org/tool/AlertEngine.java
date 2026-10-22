package org.tool;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Counts how many seconds in a row each rule has been breached; fires once when the count reaches the duration. */
public class AlertEngine {

    /** firing = metrics currently over their limit long enough; fired = messages for rules that JUST triggered. */
    public record Result(Set<String> firing, List<String> fired) {}

    private final Map<Integer, Integer> counters = new HashMap<>();
    private List<Rule> rules = List.of();

    public synchronized void setRules(List<Rule> newRules) {
        rules = List.copyOf(newRules);
        counters.clear();
    }

    public synchronized Result evaluate(Sampler.Snapshot s) {
        Set<String> firing = new HashSet<>();
        List<String> fired = new ArrayList<>();
        for (Rule r : rules) {
            double v = valueOf(r.metric(), s);
            if (!r.enabled() || v < 0) {      // disabled rule, or metric not available (no battery)
                counters.remove(r.id());
                continue;
            }
            boolean breached = r.op().equals(">") ? v > r.threshold() : v < r.threshold();
            if (breached) {
                int n = counters.merge(r.id(), 1, Integer::sum);
                if (n >= r.seconds()) firing.add(r.metric());
                if (n == r.seconds()) {
                    fired.add(String.format("%s at %.0f%%  (rule: %s %.0f%% for %ds)",
                            r.metric(), v, r.op(), r.threshold(), r.seconds()));
                }
            } else {
                counters.remove(r.id());
            }
        }
        return new Result(firing, fired);
    }

    private static double valueOf(String metric, Sampler.Snapshot s) {
        return switch (metric) {
            case "CPU" -> s.cpu();
            case "MEMORY" -> s.mem();
            case "DISK" -> s.disk();
            case "BATTERY" -> s.battery();
            default -> -1;
        };
    }
}
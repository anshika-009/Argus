package org.tool;

import atlantafx.base.theme.PrimerDark;
import atlantafx.base.theme.PrimerLight;
import javafx.animation.AnimationTimer;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.stage.Stage;
import org.tool.alert.AlertEngine;
import org.tool.alert.NotificationService;
import org.tool.process.ProcessMonitor;
import org.tool.process.ProcessTerminator;
import org.tool.process.ProtectedProcessPolicy;
import org.tool.sampler.MetricsSampler;
import org.tool.storage.SettingsRepository;
import org.tool.ui.CompactController;
import org.tool.ui.DashboardController;
import org.tool.ui.Theme;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.tool.sampler.MetricsSampler.METRICS;

/**
 * Argus - lightweight system monitor. The application object: it creates every component, connects them,
 * starts the background threads and owns the two windows (main and compact) and the theme.
 */
public class ArgusApp extends Application implements DashboardController.Host {

    private Stage stage;
    private SettingsRepository repository;
    private NotificationService notifications;
    private AlertEngine alertEngine;
    private MetricsSampler sampler;
    private ProcessMonitor processMonitor;
    private DashboardController dashboard;
    private CompactController compact;
    private AnimationTimer renderer;
    private boolean stopped;

    @Override
    public void start(Stage primary) throws Exception {
        Application.setUserAgentStylesheet(new PrimerDark().getUserAgentStylesheet());   // dark is the default look
        Platform.setImplicitExit(false);
        stage = primary;
        for (int size : new int[]{16, 32, 48, 64, 128, 256}) {
            stage.getIcons().add(new Image(ArgusApp.class.getResource("/icons/app_" + size + ".png").toExternalForm()));
        }

        // ---- the components
        repository = new SettingsRepository();
        notifications = new NotificationService();
        alertEngine = new AlertEngine(repository, notifications);
        sampler = new MetricsSampler(alertEngine);
        processMonitor = new ProcessMonitor();
        ProcessTerminator terminator = new ProcessTerminator(new ProtectedProcessPolicy());
        Set<String> compactMetrics = loadCompactMetrics();

        // ---- the two windows
        compact = new CompactController(new Stage(), stage.getIcons(), alertEngine, compactMetrics, this::showMain);
        dashboard = new DashboardController(stage, sampler, alertEngine, processMonitor, terminator,
                repository, compactMetrics, this);
        Scene scene = new Scene(dashboard.getRoot(), 1180, 720);
        scene.getStylesheets().add(Theme.STYLESHEET);
        stage.setTitle("Argus");
        stage.setScene(scene);
        stage.setMinWidth(1100);
        stage.setMinHeight(600);
        applyTheme(!"light".equals(repository.getSetting("theme", "night")), false);   // restore saved theme (dark by default)

        // ---- connections
        sampler.addListener(dashboard);
        sampler.addListener(compact);
        alertEngine.setOnEvent(() -> Platform.runLater(dashboard::refreshEvents));
        notifications.install(() -> Platform.runLater(this::showMain), () -> Platform.runLater(this::quit));
        stage.setOnCloseRequest(e -> {
            if (notifications.isTrayAvailable()) {      // keep monitoring from the tray
                e.consume();
                stage.hide();
            } else {
                quit();
            }
        });
        stage.show();
        dashboard.refreshRules();
        dashboard.refreshEvents();

        // ---- go: background threads, then the once-per-frame renderer on the JavaFX thread
        sampler.start();
        processMonitor.start();
        renderer = new AnimationTimer() {
            @Override
            public void handle(long now) {
                sampler.dispatchLatest();               // newest snapshot only; stale ones are dropped
                dashboard.pollProcesses();
            }
        };
        renderer.start();
    }

    /** Called by JavaFX when the application ends: stops the background threads and removes the tray icon. */
    @Override
    public void stop() {
        if (stopped) return;
        stopped = true;
        if (renderer != null) renderer.stop();
        if (sampler != null) sampler.stop();
        if (processMonitor != null) processMonitor.stop();
        if (notifications != null) notifications.remove();
    }

    private void quit() {
        stop();
        Platform.exit();
        System.exit(0);
    }

    // ---------------------------------------------------------------- windows

    @Override
    public void showCompact() {
        stage.hide();
        compact.show();
    }

    private void showMain() {
        compact.hide();
        stage.show();
        stage.toFront();
    }

    // ---------------------------------------------------------------- theme and settings

    @Override
    public void applyTheme(boolean night) {
        applyTheme(night, true);
    }

    /** Switches the AtlantaFX base theme and the "night" palette on both windows. */
    private void applyTheme(boolean night, boolean save) {
        Application.setUserAgentStylesheet(night
                ? new PrimerDark().getUserAgentStylesheet()
                : new PrimerLight().getUserAgentStylesheet());
        dashboard.setNight(night);
        compact.setNight(night);
        if (save) repository.setSetting("theme", night ? "night" : "light");
    }

    @Override
    public void compactMetricsChanged(Set<String> metrics) {
        List<String> chosen = new ArrayList<>();
        for (String m : METRICS) {
            if (metrics.contains(m)) chosen.add(m);
        }
        repository.setSetting("compact.metrics", String.join(",", chosen));
        compact.setVisibleMetrics(new HashSet<>(chosen));
    }

    private Set<String> loadCompactMetrics() {
        Set<String> chosen = new HashSet<>();
        String saved = repository.getSetting("compact.metrics", String.join(",", METRICS));
        for (String m : saved.split(",")) {
            String t = m.trim();
            if (Arrays.asList(METRICS).contains(t)) chosen.add(t);
        }
        if (chosen.isEmpty()) chosen.addAll(Arrays.asList(METRICS));
        return chosen;
    }
}

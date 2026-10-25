package org.tool;

import atlantafx.base.theme.PrimerDark;
import atlantafx.base.theme.PrimerLight;
import javafx.animation.AnimationTimer;
import javafx.animation.Animation;
import javafx.animation.FadeTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.css.PseudoClass;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.chart.AreaChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.Tooltip;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.image.Image;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import javafx.scene.shape.Circle;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.RowConstraints;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.awt.Graphics2D;
import java.io.InputStream;
import javax.imageio.ImageIO;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.image.BufferedImage;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/** Argus - lightweight system monitor. Flat grid theme: dark (default) and beige light. */
public class Main extends Application {

    private static final int HISTORY = 60;                       // seconds of graph history (ring buffer)
    private static final PseudoClass WARN = PseudoClass.getPseudoClass("warn");
    private static final PseudoClass ON = PseudoClass.getPseudoClass("on");
    private static final PseudoClass ELEVATED = PseudoClass.getPseudoClass("elevated");   // getting high, no alert yet
    private static final String[] METRICS = {"CPU", "MEMORY", "DISK", "BATTERY"};

    private final Sampler sampler = new Sampler();
    private final AlertEngine engine = new AlertEngine();
    private Db db;
    private ScheduledExecutorService exec;
    private String css;

    private Stage stage;
    private boolean dark = true;                  // true = dark (default), false = light (beige)
    private VBox compactRows;
    private final Map<String, HBox> compactRowNodes = new LinkedHashMap<>();
    private final Set<String> compactMetrics = new HashSet<>();   // which metrics the compact widget shows
    private Button themeBtn;
    private ToggleButton bDash, bProc, bAlert, bSet;
    private Label alertTileText;
    private Label topProcName;
    private Label topProcSub;
    private int ruleTotal;
    private int ruleActive;
    private HBox banner;
    private Label bannerText;
    private Label bannerChip;
    private Region logoRule;
    private Label tagline;
    private final Map<String, Integer> elevatedSecs = new LinkedHashMap<>();   // consecutive seconds each metric has been high
    private static final PseudoClass ALERT = PseudoClass.getPseudoClass("alert");
    private static final int WARN_AFTER_SECS = 30;
    private String bannerKey = "";
    private String bannerDismissedKey = "";
    private Label footerLeft;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    private final TextField procFilter = new TextField();
    private final Label procCount = new Label();
    private Stage compact;
    private TrayIcon tray;

    private final Map<String, MetricCard> cards = new LinkedHashMap<>();
    private final Map<String, ProgressBar> compactBars = new LinkedHashMap<>();
    private final Map<String, Label> compactLabels = new LinkedHashMap<>();

    private final TableView<Sampler.Proc> procTable = new TableView<>();
    private final TableView<Rule> ruleTable = new TableView<>();
    private final ListView<String> logView = new ListView<>();
    private List<Sampler.Proc> lastProcs = new ArrayList<>();
    private boolean sortByCpu = true;
    private int selectedPid = -1;          // process the user picked; stays selected until it exits or they deselect it
    private boolean refreshingTable;       // true while the table is rebuilt in code, so that is not mistaken for a click
    private int tickCount = 0;

    // The sampler thread PUBLISHES data here; the UI thread only READS the newest value once per frame.
    // If the UI falls behind, older frames are simply overwritten and skipped (frame dropping).
    private record Frame(Sampler.Snapshot s, AlertEngine.Result r) {}
    private final AtomicReference<Frame> latestFrame = new AtomicReference<>();
    private final AtomicReference<List<Sampler.Proc>> latestProcs = new AtomicReference<>();
    private final Map<String, RingBuffer> histories = new LinkedHashMap<>();   // fixed-size graph history
    private Frame shownFrame;
    private List<Sampler.Proc> shownProcs;

    // ---------------------------------------------------------------- start

    @Override
    public void start(Stage primary) throws Exception {
        Application.setUserAgentStylesheet(new PrimerDark().getUserAgentStylesheet());   // dark is the default look
        Platform.setImplicitExit(false);
        css = Main.class.getResource("/argus.css").toExternalForm();
        stage = primary;

        for (int size : new int[]{16, 32, 48, 64, 128, 256}) {
            stage.getIcons().add(new Image(Main.class.getResource("/icons/app_" + size + ".png").toExternalForm()));
        }

        db = new Db();
        engine.setRules(db.rules());
        loadCompactMetrics();

        // ---- header with navigation
        ToggleGroup nav = new ToggleGroup();
        bDash = navButton("Dashboard", nav);
        bProc = navButton("Processes", nav);
        bAlert = navButton("Alerts", nav);
        bSet = navButton("Settings", nav);
        bDash.setSelected(true);
        keepOneSelected(nav);

        HBox logo = buildLogo(false);
        logo.setPadding(new Insets(0, 28, 0, 24));
        logoRule = new Region();
        logoRule.getStyleClass().add("vrule");
        logoRule.setMinWidth(1);
        logoRule.setPrefWidth(1);
        logoRule.setMaxWidth(1);
        tagline = new Label("SYSTEM MONITOR");
        tagline.getStyleClass().add("tagline");
        Label status = new Label("\u25CF LIVE \u00b7 1S");
        status.getStyleClass().add("status");
        FadeTransition pulse = new FadeTransition(Duration.seconds(1), status);
        pulse.setFromValue(1.0);
        pulse.setToValue(0.45);
        pulse.setCycleCount(Animation.INDEFINITE);
        pulse.setAutoReverse(true);
        pulse.play();
        Button compactBtn = new Button("COMPACT MODE");
        compactBtn.getStyleClass().addAll("btn", "btn-solid");
        compactBtn.setOnAction(e -> showCompact());
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        themeBtn = new Button("LIGHT MODE");
        themeBtn.getStyleClass().add("btn");
        themeBtn.setOnAction(e -> applyTheme(!dark, true));
        HBox navBox = new HBox(6, bDash, bProc, bAlert, bSet);
        navBox.setAlignment(Pos.CENTER_LEFT);
        HBox right = new HBox(12, status, themeBtn, compactBtn);
        right.setAlignment(Pos.CENTER_RIGHT);
        HBox header = new HBox(logo, logoRule, tagline, navBox, spacer, right);
        HBox.setMargin(navBox, new Insets(0, 0, 0, 4));
        header.getStyleClass().add("header");
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPrefHeight(68);
        header.setMinHeight(68);

        // ---- pages
        VBox dashboard = buildDashboard();
        VBox processes = buildProcesses();
        VBox alerts = buildAlerts();
        VBox settings = buildSettings();
        StackPane content = new StackPane(dashboard, processes, alerts, settings);
        processes.setVisible(false);
        alerts.setVisible(false);
        settings.setVisible(false);
        bDash.setOnAction(e -> show(dashboard, processes, alerts, settings));
        bProc.setOnAction(e -> show(processes, dashboard, alerts, settings));
        bAlert.setOnAction(e -> show(alerts, dashboard, processes, settings));
        bSet.setOnAction(e -> show(settings, dashboard, processes, alerts));

        buildBanner();
        HBox footer = buildFooter();
        VBox rootBox = new VBox(header, banner, content, footer);
        VBox.setVgrow(content, Priority.ALWAYS);

        Scene scene = new Scene(rootBox, 1180, 720);
        scene.getStylesheets().add(css);
        stage.setTitle("Argus");
        stage.setScene(scene);
        stage.setMinWidth(1100);
        stage.setMinHeight(600);

        buildCompact();
        applyTheme(!"light".equals(db.getSetting("theme", "night")), false);   // restore saved theme (dark by default)
        setupTray();
        stage.setOnCloseRequest(e -> {
            if (tray != null) {          // keep monitoring from the tray
                e.consume();
                stage.hide();
            } else {
                quit();
            }
        });
        stage.show();
        refreshRules();
        refreshLogs();

        for (String m : METRICS) histories.put(m, new RingBuffer(HISTORY));

        exec = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "argus-sampler");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);   // never compete with the UI under heavy load
            return t;
        });
        exec.scheduleAtFixedRate(this::tick, 0, 1, TimeUnit.SECONDS);

        // Runs once per rendered frame on the JavaFX thread; draws only the newest published data.
        new AnimationTimer() {
            @Override
            public void handle(long now) {
                Frame f = latestFrame.get();
                if (f != null && f != shownFrame) {
                    shownFrame = f;
                    render(f);
                }
                List<Sampler.Proc> p = latestProcs.get();
                if (p != null && p != shownProcs) {
                    shownProcs = p;
                    lastProcs = p;
                    if (stage.isShowing()) {
                        updateTopProcess();
                        refreshProcTable(false);
                    }
                }
            }
        }.start();
    }

    // ---------------------------------------------------------------- sampling loop (background thread)

    private void tick() {
        try {                                   // an exception would silently cancel the schedule
            Sampler.Snapshot s = sampler.sample();
            AlertEngine.Result r = engine.evaluate(s);

            histories.get("CPU").add(Math.max(s.cpu(), 0));
            histories.get("MEMORY").add(Math.max(s.mem(), 0));
            histories.get("DISK").add(Math.max(s.disk(), 0));
            histories.get("BATTERY").add(Math.max(s.battery(), 0));
            latestFrame.set(new Frame(s, r));   // publish; the UI picks up the newest frame

            if (!r.fired().isEmpty()) {
                for (String msg : r.fired()) {
                    db.addLog(msg);
                    if (tray != null) tray.displayMessage("Argus alert", msg, TrayIcon.MessageType.WARNING);
                }
                Platform.runLater(this::refreshLogs);
            }
            if (tickCount++ % 2 == 0) {
                try {
                    latestProcs.set(sampler.processes());
                } catch (Exception e) {
                    e.printStackTrace();          // a process-read failure must not stop the metrics
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /** JavaFX thread: draw one published frame. */
    private void render(Frame f) {
        Sampler.Snapshot s = f.s();
        AlertEngine.Result r = f.r();
        update("CPU", s.cpu(), pct(s.cpu()), s.cpu() < 0 ? "N/A" : sampler.cpuSummary(), r);
        update("MEMORY", s.mem(), pct(s.mem()), s.memText(), r);
        update("DISK", s.disk(), pct(s.disk()), s.diskText(), r);
        update("BATTERY", s.battery(), pct(s.battery()), s.batText(), r);
        if (stage.isShowing()) {
            footerLeft.setText("LAST SAMPLE " + LocalTime.now().format(TIME) + " \u00b7 EVERY 1S \u00b7 " + HISTORY
                    + "S HISTORY \u00b7 UP " + fmtUptime(s.uptimeSecs()));
        }
        trackElevated("CPU", s.cpu());
        trackElevated("MEMORY", s.mem());
        trackElevated("DISK", s.disk());
        updateBanner(r.firing(), s);
    }

    private void trackElevated(String metric, double v) {
        boolean high = v >= 70;
        elevatedSecs.merge(metric, 1, (old, one) -> old + 1);
        if (!high) elevatedSecs.put(metric, 0);
    }

    /**
     * Banner under the header. RED "ALERT" while an alert rule is firing; otherwise amber "WARNING"
     * once CPU, memory or disk has stayed above 70% for 30 seconds. Dismissable until the cause changes.
     */
    private void updateBanner(Set<String> firing, Sampler.Snapshot s) {
        java.util.TreeSet<String> names = new java.util.TreeSet<>(firing);
        String key = "";
        String text = "";
        boolean isAlert = !names.isEmpty();
        if (isAlert) {
            key = "A:" + String.join(",", names);
            text = String.join(", ", names)
                    + (names.size() == 1 ? " has crossed its alert limit" : " have crossed their alert limits");
        } else {
            String worst = null;
            for (String m : new String[]{"CPU", "MEMORY", "DISK"}) {
                if (elevatedSecs.getOrDefault(m, 0) >= WARN_AFTER_SECS
                        && (worst == null || valueOf(s, m) > valueOf(s, worst))) worst = m;
            }
            if (worst != null) {
                key = "W:" + worst;
                text = String.format("%s at %.0f%% for %s.%s", titleCase(worst), valueOf(s, worst),
                        fmtDur(elevatedSecs.get(worst)), topCause(worst));
            }
        }
        bannerKey = key;
        if (key.isEmpty()) bannerDismissedKey = "";
        boolean show = !key.isEmpty() && !key.equals(bannerDismissedKey);
        if (show) {
            bannerChip.setText(isAlert ? "ALERT" : "WARNING");
            bannerChip.getStyleClass().setAll("tag", isAlert ? "tag-solid-bad" : "tag-solid-warn");
            bannerText.setText(text);
            banner.pseudoClassStateChanged(ALERT, isAlert);
        }
        banner.setVisible(show);
        banner.setManaged(show);
        updateAlertTile(names);
    }

    private static double valueOf(Sampler.Snapshot s, String metric) {
        return switch (metric) {
            case "CPU" -> s.cpu();
            case "MEMORY" -> s.mem();
            case "DISK" -> s.disk();
            default -> s.battery();
        };
    }

    private static String titleCase(String metric) {
        return metric.charAt(0) + metric.substring(1).toLowerCase();
    }

    private static String fmtDur(int secs) {
        return secs < 60 ? secs + "s" : (secs / 60) + "m " + String.format("%02d", secs % 60) + "s";
    }

    /** " Top cause: name (1.75 GB)." for CPU and memory; empty for disk. */
    private String topCause(String metric) {
        if (metric.equals("DISK")) return "";
        boolean mem = metric.equals("MEMORY");
        Comparator<Sampler.Proc> heaviest = mem
                ? Comparator.comparingLong(Sampler.Proc::memBytes)
                : Comparator.comparingDouble(Sampler.Proc::cpu);
        return lastProcs.stream()
                .filter(p -> p.pid() != 0 && !p.name().toLowerCase().contains("idle"))
                .max(heaviest)
                .map(p -> " Top cause: " + p.name() + " ("
                        + (mem ? fmtMem(p.memBytes()) : String.format("%.1f%% CPU", p.cpu())) + ").")
                .orElse("");
    }

    private void buildBanner() {
        bannerChip = new Label("WARNING");
        bannerChip.getStyleClass().addAll("tag", "tag-solid-warn");
        bannerText = new Label();
        Region sp = new Region();
        HBox.setHgrow(sp, Priority.ALWAYS);
        Button view = new Button("VIEW PROCESSES");
        view.getStyleClass().addAll("btn", "btn-solid");
        view.setOnAction(e -> bProc.fire());
        Button close = new Button("\u2715");
        close.getStyleClass().addAll("btn", "btn-ghost");
        close.setOnAction(e -> {
            bannerDismissedKey = bannerKey;
            banner.setVisible(false);
            banner.setManaged(false);
        });
        banner = new HBox(14, bannerChip, bannerText, sp, view, close);
        banner.getStyleClass().add("banner");
        banner.setVisible(false);
        banner.setManaged(false);
        VBox.setMargin(banner, new Insets(20, 24, 0, 24));
    }

    private HBox buildFooter() {
        footerLeft = new Label("WAITING FOR FIRST SAMPLE");
        footerLeft.getStyleClass().add("footer-label");
        Label right = new Label("ARGUS 1.0 \u00b7 OFFLINE \u00b7 " + System.getProperty("os.name", "").toUpperCase());
        right.getStyleClass().add("footer-label");
        Region sp = new Region();
        HBox.setHgrow(sp, Priority.ALWAYS);
        HBox footer = new HBox(footerLeft, sp, right);
        footer.getStyleClass().add("footer");
        return footer;
    }

    private void update(String metric, double v, String valueText, String subText, AlertEngine.Result r) {
        boolean firing = r.firing().contains(metric);
        // early warning: amber when a value is getting high (or a battery low) but no alert has fired yet
        boolean elevated = v >= 0 && (metric.equals("BATTERY") ? v <= 30 : v >= 70);
        if (stage.isShowing()) {                // no point redrawing charts nobody can see
            cards.get(metric).draw(histories.get(metric).toArray(), valueText, subText, firing, elevated);
        }
        if (compact.isShowing()) {
            compactBars.get(metric).setProgress(Math.max(v, 0) / 100.0);
            compactBars.get(metric).setStyle(firing ? "-fx-accent: -argus-warn;"
                    : elevated ? "-fx-accent: -argus-amber;" : "-fx-accent: -argus-ok;");
            compactLabels.get(metric).setText(valueText);
        }
    }

    private static String pct(double v) {
        return v < 0 ? "N/A" : String.format("%.0f%%", v);
    }

    // ---------------------------------------------------------------- dashboard

    /** One metric cell: number badge + status tag, title, spec line, sparkline (ring buffer of the last 60 samples), big value. */
    private final class MetricCard {
        final String metric;
        final Label num = new Label("--");
        final Label unit = new Label();
        final Label sub = new Label(" ");
        final Label tag = new Label("--");
        final XYChart.Series<Number, Number> series = new XYChart.Series<>();
        final VBox box;
        final NumberAxis xAxis;

        MetricCard(String title, String metric, int index) {
            this.metric = metric;
            NumberAxis x = new NumberAxis(0, HISTORY - 1, 1);
            xAxis = x;
            NumberAxis y = new NumberAxis(0, 100, 50);          // scale: 0 / 50 / 100 %
            for (NumberAxis ax : List.of(x, y)) {
                ax.setTickLabelsVisible(false);
                ax.setTickMarkVisible(false);
                ax.setMinorTickVisible(false);
                ax.setOpacity(0);
            }
            y.setPrefWidth(0);
            AreaChart<Number, Number> chart = new AreaChart<>(x, y);
            chart.setCreateSymbols(false);
            chart.setLegendVisible(false);
            chart.setAnimated(false);
            chart.setHorizontalGridLinesVisible(false);
            chart.setVerticalGridLinesVisible(false);
            chart.getData().add(series);
            chart.setMinHeight(70);
            VBox.setVgrow(chart, Priority.ALWAYS);

            Label badge = new Label(String.format("%02d", index));
            badge.getStyleClass().add("badge");
            Region sp = new Region();
            HBox.setHgrow(sp, Priority.ALWAYS);
            tag.getStyleClass().addAll("tag", "tag-dim");
            HBox top = new HBox(badge, sp, tag);
            top.setAlignment(Pos.CENTER_LEFT);

            Label name = new Label(title);
            name.getStyleClass().add("tile-title");
            VBox.setMargin(name, new Insets(14, 0, 0, 0));
            sub.getStyleClass().add("sub");
            sub.setMinHeight(18);
            num.getStyleClass().add("big");
            unit.getStyleClass().add("unit");
            HBox valueRow = new HBox(4, num, unit);
            valueRow.setAlignment(Pos.BASELINE_LEFT);

            box = new VBox(top, name, sub, chart, valueRow);
            box.getStyleClass().add("card");
        }

        /** data = ring-buffer contents, oldest first. */
        void draw(double[] data, String valueText, String subText, boolean firing, boolean elevated) {
            List<XYChart.Data<Number, Number>> pts = new ArrayList<>(data.length);
            for (int i = 0; i < data.length; i++) pts.add(new XYChart.Data<Number, Number>(i, data[i]));
            series.getData().setAll(pts);
            xAxis.setUpperBound(Math.max(data.length - 1, 12));     // fill the width early on, settle at 60 samples
            boolean percent = valueText.endsWith("%");
            num.setText(percent ? valueText.substring(0, valueText.length() - 1) : valueText);
            unit.setText(percent ? "%" : "");
            sub.setText(subText.replaceAll("\\s+", " ").toUpperCase());
            box.pseudoClassStateChanged(WARN, firing);
            box.pseudoClassStateChanged(ELEVATED, elevated && !firing);

            boolean na = valueText.equals("N/A");
            tag.setText(firing ? "ALERT" : elevated ? (metric.equals("BATTERY") ? "LOW" : "ELEVATED") : na ? "N/A" : "NORMAL");
            tag.getStyleClass().setAll("tag", firing ? "tag-bad" : elevated ? "tag-warn" : na ? "tag-dim" : "tag-ok");
        }
    }

    private VBox buildDashboard() {
        GridPane grid = new GridPane();
        grid.getStyleClass().add("grid-frame");        // 1px frame; cells draw their own right/bottom rules
        for (int i = 0; i < 4; i++) {
            ColumnConstraints cc = new ColumnConstraints();
            cc.setPercentWidth(25);
            grid.getColumnConstraints().add(cc);
        }
        RowConstraints upper = new RowConstraints();
        upper.setPercentHeight(64);
        upper.setVgrow(Priority.ALWAYS);
        RowConstraints lower = new RowConstraints();
        lower.setPercentHeight(36);
        lower.setVgrow(Priority.ALWAYS);
        grid.getRowConstraints().addAll(upper, lower);

        int col = 0;
        for (String m : METRICS) {
            MetricCard card = new MetricCard(tileTitle(m), m, col + 1);
            cards.put(m, card);
            grid.add(card.box, col++, 0);
        }
        grid.add(buildAlertTile(), 0, 1, 2, 1);
        grid.add(buildTopProcessTile(), 2, 1, 2, 1);

        VBox page = new VBox(grid);
        page.setPadding(new Insets(20, 24, 0, 24));
        VBox.setVgrow(grid, Priority.ALWAYS);
        return page;
    }

    private static String tileTitle(String metric) {
        return switch (metric) {
            case "CPU" -> "CPU load";
            case "MEMORY" -> "Memory";
            case "DISK" -> "Hard disk";
            default -> "Battery";
        };
    }

    private VBox buildAlertTile() {
        Label eyebrow = new Label("ALERT RULES");
        eyebrow.getStyleClass().add("eyebrow");
        alertTileText = new Label("Loading rules...");
        alertTileText.getStyleClass().add("tile-title");
        alertTileText.setWrapText(true);
        Button manage = new Button("MANAGE ALERTS");
        manage.getStyleClass().add("btn");
        manage.setOnAction(e -> bAlert.fire());
        Region sp = new Region();
        VBox.setVgrow(sp, Priority.ALWAYS);
        VBox tile = new VBox(8, eyebrow, alertTileText, manage, sp);
        tile.getStyleClass().add("tile");
        tile.setAlignment(Pos.CENTER_LEFT);
        VBox.setMargin(manage, new Insets(6, 0, 0, 0));
        return tile;
    }

    private VBox buildTopProcessTile() {
        Label eyebrow = new Label("HEAVIEST PROCESS");
        eyebrow.getStyleClass().add("eyebrow");
        topProcName = new Label("Waiting for data...");
        topProcName.getStyleClass().add("tile-title");
        topProcSub = new Label(" ");
        topProcSub.getStyleClass().add("sub");
        Button view = new Button("VIEW PROCESSES");
        view.getStyleClass().addAll("btn", "btn-solid");
        view.setOnAction(e -> bProc.fire());
        Region sp = new Region();
        VBox.setVgrow(sp, Priority.ALWAYS);
        VBox tile = new VBox(8, eyebrow, topProcName, topProcSub, view, sp);
        tile.getStyleClass().add("tile");
        VBox.setMargin(view, new Insets(6, 0, 0, 0));
        return tile;
    }

    private void updateTopProcess() {
        lastProcs.stream()
                .filter(p -> p.pid() != 0 && !p.name().toLowerCase().contains("idle"))
                .max(Comparator.comparingDouble(Sampler.Proc::cpu))
                .ifPresent(p -> {
                    topProcName.setText(p.name());
                    topProcSub.setText(String.format("%.1f%% CPU \u00b7 %s \u00b7 PID %d",
                            p.cpu(), fmtMem(p.memBytes()), p.pid()).toUpperCase());
                });
    }

    private void updateAlertTile(Set<String> firing) {
        if (alertTileText == null) return;
        if (!firing.isEmpty()) {
            alertTileText.setText(String.join(", ", firing) + (firing.size() == 1 ? " is over its limit" : " are over their limits"));
        } else if (ruleActive == 0) {
            alertTileText.setText("No rules switched on");
        } else {
            alertTileText.setText("All clear \u00b7 " + ruleActive + " of " + ruleTotal + " rules watching");
        }
    }

    // ---------------------------------------------------------------- processes

    private VBox buildProcesses() {
        ToggleGroup sort = new ToggleGroup();
        ToggleButton byCpu = sortButton("CPU", sort);
        ToggleButton byMem = sortButton("MEMORY", sort);
        byCpu.setSelected(true);
        keepOneSelected(sort);
        sort.selectedToggleProperty().addListener((o, a, b) -> {
            sortByCpu = byCpu.isSelected();
            refreshProcTable(true);
        });

        Button kill = new Button("TERMINATE");
        kill.getStyleClass().addAll("btn", "btn-danger");
        kill.setOnAction(e -> terminateSelected());
        Region sp = new Region();
        HBox.setHgrow(sp, Priority.ALWAYS);
        Label sortLbl = new Label("SORT BY");
        sortLbl.getStyleClass().add("eyebrow");
        procFilter.setPromptText("filter by name...");
        procFilter.setPrefWidth(180);
        procFilter.textProperty().addListener((o, a, b) -> refreshProcTable(true));
        procCount.getStyleClass().addAll("mono", "dim");
        HBox bar = new HBox(10, sortLbl, byCpu, byMem, procFilter, procCount, sp, kill);
        bar.setAlignment(Pos.CENTER_LEFT);

        procTable.getColumns().add(col("NAME", Sampler.Proc::name, 300));
        TableColumn<Sampler.Proc, String> pidCol = col("PID", p -> String.valueOf(p.pid()), 100);
        TableColumn<Sampler.Proc, String> cpuCol = col("CPU", p -> String.format("%.1f%%", p.cpu()), 100);
        TableColumn<Sampler.Proc, String> memCol = col("MEMORY", p -> fmtMem(p.memBytes()), 120);
        for (TableColumn<Sampler.Proc, String> c : List.of(pidCol, cpuCol, memCol)) {
            c.setStyle("-fx-alignment: CENTER-RIGHT;");
        }
        cpuCol.setCellFactory(c -> new TableCell<>() {         // heavy CPU users are shown in the warning colour
            @Override
            protected void updateItem(String text, boolean empty) {
                super.updateItem(text, empty);
                setText(empty ? null : text);
                getStyleClass().remove("hot");
                if (!empty && getIndex() >= 0 && getIndex() < getTableView().getItems().size()
                        && getTableView().getItems().get(getIndex()).cpu() >= 50
                        && getTableView().getItems().get(getIndex()).pid() != 0) {   // Idle's % is spare CPU, never "hot"
                    getStyleClass().add("hot");
                }
            }
        });
        procTable.getColumns().addAll(pidCol, cpuCol, memCol);
        // PID 0 is Windows' "System Idle Process": a placeholder for unused CPU time, not a real program.
        procTable.setRowFactory(tv -> new TableRow<>() {
            @Override
            protected void updateItem(Sampler.Proc p, boolean empty) {
                super.updateItem(p, empty);
                if (empty || p == null || p.pid() != 0) {
                    setTooltip(null);
                    return;
                }
                Sampler.Snapshot s = shownFrame == null ? null : shownFrame.s();
                String free = s == null || s.memFreeBytes() < 0 ? ""
                        : String.format("\n%s of your RAM is still free.", fmtMem(s.memFreeBytes()));
                setTooltip(new Tooltip(p.name() + " is a Windows placeholder, not a real program."
                        + "\nIts " + String.format("%.1f%%", p.cpu()) + " is the share of processor time nobody is using."
                        + "\nIt holds no memory, so it can't cause a memory alert." + free));
            }
        });
        procTable.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> {
            if (!refreshingTable) selectedPid = b == null ? -1 : b.pid();      // only real clicks change the pick
        });
        procTable.setPlaceholder(new Label("No matching processes"));
        procTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        VBox.setVgrow(procTable, Priority.ALWAYS);

        VBox page = new VBox(14, bar, procTable);
        page.setPadding(new Insets(20));
        return page;
    }

    /**
     * resort = false (the 2-second timer): while a process is selected the rows keep their order and only the numbers
     * update, so the row you picked does not jump away. resort = true (sort or filter changed): rebuild the ranking.
     * The selected process is always kept in the table until it exits or the user deselects it.
     */
    private void refreshProcTable(boolean resort) {
        Map<Integer, Sampler.Proc> byPid = new HashMap<>();
        for (Sampler.Proc p : lastProcs) byPid.put(p.pid(), p);
        if (selectedPid != -1 && !byPid.containsKey(selectedPid)) selectedPid = -1;      // it exited

        String needle = procFilter.getText() == null ? "" : procFilter.getText().trim().toLowerCase();
        if (selectedPid != -1 && !needle.isEmpty() && !byPid.get(selectedPid).name().toLowerCase().contains(needle)) {
            selectedPid = -1;                                                              // filtered out on purpose
        }

        Comparator<Sampler.Proc> cmp = sortByCpu
                ? Comparator.comparingDouble(Sampler.Proc::cpu).reversed()
                : Comparator.comparingLong(Sampler.Proc::memBytes).reversed();
        List<Sampler.Proc> matching = lastProcs.stream()
                .filter(p -> needle.isEmpty() || p.name().toLowerCase().contains(needle))
                .sorted(cmp).toList();

        boolean locked = selectedPid != -1 && !resort && !procTable.getItems().isEmpty();
        List<Sampler.Proc> shown = new ArrayList<>();
        if (locked) {
            for (Sampler.Proc old : procTable.getItems()) {                // same order, fresh numbers
                Sampler.Proc cur = byPid.get(old.pid());
                if (cur != null) shown.add(cur);
            }
        } else {
            shown.addAll(matching.stream().limit(25).toList());
        }
        if (selectedPid != -1 && shown.stream().noneMatch(p -> p.pid() == selectedPid)) {
            shown.add(byPid.get(selectedPid));                             // keep it even if it fell out of the top 25
        }

        procCount.setText("showing " + shown.size() + " of " + matching.size() + (locked ? " \u00b7 order locked while selected" : ""));
        refreshingTable = true;
        try {
            procTable.getItems().setAll(shown);
            if (selectedPid != -1) {
                for (Sampler.Proc p : shown) {
                    if (p.pid() == selectedPid) {
                        procTable.getSelectionModel().select(p);
                        break;
                    }
                }
            }
        } finally {
            refreshingTable = false;
        }
    }

    private void terminateSelected() {
        Sampler.Proc p = procTable.getSelectionModel().getSelectedItem();
        if (p == null) {
            info("Select a process first.", Alert.AlertType.INFORMATION);
            return;
        }

        // Safety checks BEFORE asking: protected / unreadable / recycled-PID processes never reach the dialog.
        ProcessGuard.Verdict first = ProcessGuard.check(p);
        if (!first.allowed()) {
            info(first.reason(), Alert.AlertType.WARNING);
            return;
        }

        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                p.name() + " (PID " + p.pid() + ")\nUnsaved data in this app will be lost.",
                ButtonType.CANCEL, ButtonType.OK);
        confirm.setTitle("Terminate process?");
        confirm.setHeaderText("TERMINATE PROCESS?");
        styleDialog(confirm);
        Optional<ButtonType> answer = confirm.showAndWait();
        if (answer.isEmpty() || answer.get() != ButtonType.OK) return;

        // Re-check: the process may have exited (and its PID been reused) while the dialog was open.
        ProcessGuard.Verdict verdict = ProcessGuard.check(p);
        if (!verdict.allowed()) {
            info(verdict.reason(), Alert.AlertType.WARNING);
            return;
        }
        ProcessHandle handle = verdict.handle();

        // Kill and wait on a background thread so the UI never freezes.
        Thread worker = new Thread(() -> {
            String error = null;
            try {
                if (!handle.destroy()) {
                    error = "Windows refused to terminate " + p.name() + " (access denied).\nTry running Argus as administrator.";
                } else {
                    handle.onExit().get(3, TimeUnit.SECONDS);
                }
            } catch (TimeoutException e) {
                error = p.name() + " did not exit within 3 seconds.";
            } catch (Exception e) {
                error = "Could not terminate " + p.name() + ": " + e.getMessage();
            }
            final String failure = error;
            Platform.runLater(() -> {
                if (failure == null) {
                    db.addLog("Terminated " + p.name() + " (PID " + p.pid() + ")");
                    refreshLogs();
                } else {
                    info(failure, Alert.AlertType.ERROR);
                }
            });
        }, "argus-terminate");
        worker.setDaemon(true);
        worker.start();
    }

    // ---------------------------------------------------------------- alerts

    private VBox buildAlerts() {
        ruleTable.getColumns().add(col("METRIC", Rule::metric, 160));
        ruleTable.getColumns().add(col("CONDITION", r -> r.op() + " " + String.format("%.0f", r.threshold()) + "%", 140));
        ruleTable.getColumns().add(col("DURATION", r -> r.seconds() + " s", 120));
        TableColumn<Rule, String> enabledCol = col("ENABLED", r -> "", 100);
        enabledCol.setCellFactory(c -> new TableCell<>() {
            @Override
            protected void updateItem(String text, boolean empty) {
                super.updateItem(text, empty);
                setText(null);
                if (empty || getIndex() < 0 || getIndex() >= getTableView().getItems().size()) {
                    setGraphic(null);
                } else {
                    setGraphic(switchFor(getTableView().getItems().get(getIndex())));
                }
            }
        });
        ruleTable.getColumns().add(enabledCol);
        ruleTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        ruleTable.setPrefHeight(200);

        ComboBox<String> metric = new ComboBox<>();
        metric.getItems().addAll(METRICS);
        metric.setValue("CPU");
        ComboBox<String> op = new ComboBox<>();
        op.getItems().addAll(">", "<");
        op.setValue(">");
        TextField threshold = new TextField();
        threshold.setPromptText("limit %");
        threshold.setPrefWidth(90);
        TextField seconds = new TextField();
        seconds.setPromptText("seconds");
        seconds.setPrefWidth(90);
        Button add = new Button("ADD RULE");
        add.getStyleClass().addAll("btn", "btn-solid");
        add.setOnAction(e -> {
            try {
                double t = Double.parseDouble(threshold.getText().trim());
                int s = Integer.parseInt(seconds.getText().trim());
                if (t < 0 || t > 100 || s < 1) throw new NumberFormatException();
                db.addRule(metric.getValue(), op.getValue(), t, s);
                threshold.clear();
                seconds.clear();
                refreshRules();
            } catch (NumberFormatException ex) {
                info("Limit must be 0-100 and seconds must be 1 or more.", Alert.AlertType.WARNING);
            }
        });

        Button toggle = new Button("TOGGLE ON/OFF");
        toggle.getStyleClass().add("btn");
        toggle.setOnAction(e -> {
            Rule r = ruleTable.getSelectionModel().getSelectedItem();
            if (r != null) {
                db.setEnabled(r.id(), !r.enabled());
                refreshRules();
            }
        });
        Button delete = new Button("DELETE");
        delete.getStyleClass().addAll("btn", "btn-danger");
        delete.setOnAction(e -> {
            Rule r = ruleTable.getSelectionModel().getSelectedItem();
            if (r != null) {
                db.deleteRule(r.id());
                refreshRules();
            }
        });

        Region sp = new Region();
        HBox.setHgrow(sp, Priority.ALWAYS);
        HBox form = new HBox(10, metric, op, threshold, seconds, add, sp, delete);
        form.setAlignment(Pos.CENTER_LEFT);

        Label rulesTitle = new Label("Alert rules");
        rulesTitle.getStyleClass().add("section");
        Label logTitle = new Label("Recent triggers");
        logTitle.getStyleClass().add("section");
        VBox.setVgrow(logView, Priority.ALWAYS);

        VBox page = new VBox(12, rulesTitle, form, ruleTable, logTitle, logView);
        page.setPadding(new Insets(20));
        return page;
    }

    /** Square on/off switch for one alert rule. */
    private StackPane switchFor(Rule r) {
        Rectangle thumb = new Rectangle(16, 16, Color.web("#f4f4f2"));
        StackPane sw = new StackPane(thumb);
        sw.getStyleClass().add("switch");
        sw.pseudoClassStateChanged(ON, r.enabled());
        sw.setMinSize(44, 24);
        sw.setPrefSize(44, 24);
        sw.setMaxSize(44, 24);
        sw.setPadding(new Insets(0, 3, 0, 3));
        StackPane.setAlignment(thumb, r.enabled() ? Pos.CENTER_RIGHT : Pos.CENTER_LEFT);
        sw.setCursor(Cursor.HAND);
        sw.setOnMouseClicked(e -> {
            db.setEnabled(r.id(), !r.enabled());
            refreshRules();
        });
        return sw;
    }

    private void refreshRules() {
        List<Rule> rules = db.rules();
        ruleTable.getItems().setAll(rules);
        engine.setRules(rules);
        ruleTotal = rules.size();
        ruleActive = (int) rules.stream().filter(Rule::enabled).count();
    }

    private void refreshLogs() {
        logView.getItems().setAll(db.recentLogs(50));
    }

    // ---------------------------------------------------------------- compact mode + tray

    private void buildCompact() {
        VBox box = new VBox(12);
        box.setPadding(new Insets(14));
        box.getStyleClass().add("compact-root");
        HBox title = buildLogo(true);
        Button expand = new Button("EXPAND");
        expand.getStyleClass().add("btn");
        expand.setOnAction(e -> showMain());
        Region sp = new Region();
        HBox.setHgrow(sp, Priority.ALWAYS);
        HBox top = new HBox(title, sp, expand);
        top.setAlignment(Pos.CENTER_LEFT);
        compactRows = new VBox(10);
        box.getChildren().addAll(top, compactRows);
        for (String m : METRICS) {
            Label name = new Label(m.substring(0, Math.min(4, m.length())));
            name.getStyleClass().add("eyebrow");
            name.setPrefWidth(44);
            ProgressBar bar = new ProgressBar(0);
            bar.setPrefWidth(150);
            Label val = new Label("--");
            val.getStyleClass().add("mono");
            val.setPrefWidth(44);
            val.setAlignment(Pos.CENTER_RIGHT);
            compactBars.put(m, bar);
            compactLabels.put(m, val);
            HBox row = new HBox(8, name, bar, val);
            row.setAlignment(Pos.CENTER_LEFT);
            compactRowNodes.put(m, row);
        }
        compact = new Stage();
        compact.setTitle("Argus");
        compact.setAlwaysOnTop(true);
        compact.setResizable(false);
        compact.getIcons().addAll(stage.getIcons());
        Scene scene = new Scene(box);
        scene.getStylesheets().add(css);
        compact.setScene(scene);
        compact.setOnCloseRequest(e -> {
            e.consume();
            showMain();
        });
        applyCompactSelection();
    }

    /** Shows only the chosen metrics, always in the same order. */
    private void applyCompactSelection() {
        compactRows.getChildren().clear();
        for (String m : METRICS) {
            if (compactMetrics.contains(m)) compactRows.getChildren().add(compactRowNodes.get(m));
        }
        if (compact.isShowing()) compact.sizeToScene();
    }

    private void loadCompactMetrics() {
        String saved = db.getSetting("compact.metrics", String.join(",", METRICS));
        for (String m : saved.split(",")) {
            String t = m.trim();
            if (Arrays.asList(METRICS).contains(t)) compactMetrics.add(t);
        }
        if (compactMetrics.isEmpty()) compactMetrics.addAll(Arrays.asList(METRICS));
    }

    private void saveCompactMetrics() {
        List<String> chosen = new ArrayList<>();
        for (String m : METRICS) {
            if (compactMetrics.contains(m)) chosen.add(m);
        }
        db.setSetting("compact.metrics", String.join(",", chosen));
    }

    // ---------------------------------------------------------------- settings page

    private VBox buildSettings() {
        Label title = new Label("Compact mode");
        title.getStyleClass().add("section");
        Label hint = new Label("Choose which metrics the small always-on-top widget shows.");
        hint.getStyleClass().add("dim");

        VBox boxes = new VBox(10);
        for (String m : METRICS) {
            CheckBox cb = new CheckBox(m);
            cb.setSelected(compactMetrics.contains(m));
            cb.setOnAction(e -> {
                if (cb.isSelected()) {
                    compactMetrics.add(m);
                } else {
                    compactMetrics.remove(m);
                }
                if (compactMetrics.isEmpty()) {                    // the widget needs at least one row
                    compactMetrics.add(m);
                    cb.setSelected(true);
                    info("Compact mode needs at least one metric.", Alert.AlertType.WARNING);
                    return;
                }
                saveCompactMetrics();
                applyCompactSelection();
            });
            boxes.getChildren().add(cb);
        }
        Label note = new Label("Your choices and theme are saved automatically and restored on the next start.");
        note.getStyleClass().add("dim");

        VBox page = new VBox(14, title, hint, boxes, note);
        page.setPadding(new Insets(20));
        return page;
    }

    private void showCompact() {
        stage.hide();
        compact.show();
    }

    private void showMain() {
        compact.hide();
        stage.show();
        stage.toFront();
    }

    private void setupTray() {
        if (!SystemTray.isSupported()) return;
        try {
            BufferedImage img = null;
            try (InputStream in = Main.class.getResourceAsStream("/icons/app_16.png")) {
                if (in != null) img = ImageIO.read(in);
            } catch (Exception ignored) {
                // use the plain fallback below
            }
            if (img == null) {
                img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
                Graphics2D g = img.createGraphics();
                g.setColor(new java.awt.Color(0x2e, 0xc4, 0xe6));
                g.fillOval(1, 1, 14, 14);
                g.dispose();
            }

            java.awt.MenuItem open = new java.awt.MenuItem("Show Argus");
            open.addActionListener(e -> Platform.runLater(this::showMain));
            java.awt.MenuItem exit = new java.awt.MenuItem("Exit");
            exit.addActionListener(e -> Platform.runLater(this::quit));
            PopupMenu menu = new PopupMenu();
            menu.add(open);
            menu.add(exit);

            tray = new TrayIcon(img, "Argus", menu);
            tray.setImageAutoSize(true);
            tray.addActionListener(e -> Platform.runLater(this::showMain));   // double-click
            SystemTray.getSystemTray().add(tray);
        } catch (Exception e) {
            tray = null;                                  // run without a tray icon
        }
    }

    private void quit() {
        if (exec != null) exec.shutdownNow();
        if (tray != null) SystemTray.getSystemTray().remove(tray);
        Platform.exit();
        System.exit(0);
    }

    // ---------------------------------------------------------------- theme

    /** Switches the AtlantaFX base theme and toggles the "night" (dark) palette class on every window. */
    private void applyTheme(boolean darkMode, boolean save) {
        dark = darkMode;
        Application.setUserAgentStylesheet(dark
                ? new PrimerDark().getUserAgentStylesheet()
                : new PrimerLight().getUserAgentStylesheet());
        setNight(stage.getScene().getRoot(), dark);
        setNight(compact.getScene().getRoot(), dark);
        themeBtn.setText(dark ? "LIGHT MODE" : "DARK MODE");
        for (Node n : new Node[]{logoRule, tagline}) {      // the dark header has a divider and a tagline; the light one does not
            n.setVisible(dark);
            n.setManaged(dark);
        }
        if (save) db.setSetting("theme", dark ? "night" : "light");
    }

    private static void setNight(Parent root, boolean night) {
        root.getStyleClass().remove("night");
        if (night) root.getStyleClass().add("night");
    }

    private void styleDialog(Alert a) {
        a.getDialogPane().getStylesheets().add(css);
        if (dark) a.getDialogPane().getStyleClass().add("night");
    }

    // ---------------------------------------------------------------- small helpers

    private static void show(VBox visible, VBox... others) {
        for (VBox o : others) o.setVisible(false);
        if (!visible.isVisible()) {                    // short fade-in when switching pages
            visible.setOpacity(0);
            visible.setVisible(true);
            FadeTransition ft = new FadeTransition(Duration.millis(180), visible);
            ft.setToValue(1);
            ft.play();
        }
    }

    private static String fmtUptime(long secs) {
        long d = secs / 86400, h = (secs % 86400) / 3600, m = (secs % 3600) / 60, sec = secs % 60;
        return d > 0 ? String.format("%dd %02d:%02d:%02d", d, h, m, sec) : String.format("%02d:%02d:%02d", h, m, sec);
    }

    /** The bundled logo face (src/main/resources/fonts/SpaceGrotesk-Bold.ttf) at the given size, or null if absent. */
    private static Font logoFont(double size) {
        try (InputStream in = Main.class.getResourceAsStream("/fonts/SpaceGrotesk-Bold.ttf")) {
            return in == null ? null : Font.loadFont(in, size);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * "Argus" in the grotesque logo face with a round red full stop.
     * If the bundled font loads as a light weight (e.g. the variable font), the letters are thickened with a
     * matching outline so the logo is bold either way. Without the font file it falls back to Segoe UI Bold.
     */
    private static HBox buildLogo(boolean small) {
        double size = small ? 20 : 34;
        Font f = logoFont(size);
        boolean fakeBold = false;
        if (f == null) {
            f = Font.font("Segoe UI", FontWeight.BOLD, size);
        } else {
            String face = (f.getName() + " " + f.getStyle()).toLowerCase();
            fakeBold = !(face.contains("bold") || face.contains("black") || face.contains("heavy"));
        }
        Text word = new Text("Argus");
        word.setFont(f);
        word.getStyleClass().add("logo-text");
        if (fakeBold) word.getStyleClass().add("logo-fake-bold");
        Circle dot = new Circle(size * 0.105);
        dot.getStyleClass().add("logo-dot-shape");
        HBox logo = new HBox(size * 0.09, word, dot);
        logo.setAlignment(Pos.BASELINE_LEFT);
        return logo;
    }

    private static ToggleButton navButton(String text, ToggleGroup g) {
        ToggleButton b = new ToggleButton(text.toUpperCase());
        b.setToggleGroup(g);
        b.getStyleClass().add("nav");
        return b;
    }

    private static ToggleButton sortButton(String text, ToggleGroup g) {
        ToggleButton b = new ToggleButton(text);
        b.setToggleGroup(g);
        b.getStyleClass().add("tab");
        return b;
    }

    /** Stops a ToggleGroup from ending up with nothing selected. */
    private static void keepOneSelected(ToggleGroup g) {
        g.selectedToggleProperty().addListener((o, oldT, newT) -> {
            if (newT == null && oldT != null) oldT.setSelected(true);
        });
    }

    private static <T> TableColumn<T, String> col(String title, Function<T, String> f, double width) {
        TableColumn<T, String> c = new TableColumn<>(title);
        c.setCellValueFactory(x -> new SimpleStringProperty(f.apply(x.getValue())));
        c.setPrefWidth(width);
        return c;
    }

    private static String fmtMem(long bytes) {
        double mb = bytes / 1048576.0;
        return mb >= 1024 ? String.format("%.2f GB", mb / 1024) : String.format("%.0f MB", mb);
    }

    private void info(String text, Alert.AlertType type) {
        Alert a = new Alert(type, text, ButtonType.OK);
        a.setHeaderText(null);
        styleDialog(a);
        a.showAndWait();
    }

    public static void main(String[] args) {
        System.setProperty("java.awt.headless", "false");
        launch(args);
    }
}
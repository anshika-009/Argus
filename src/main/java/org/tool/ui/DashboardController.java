package org.tool.ui;

import javafx.animation.Animation;
import javafx.animation.FadeTransition;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.css.PseudoClass;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.chart.AreaChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.RowConstraints;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.stage.Stage;
import javafx.util.Duration;
import org.tool.alert.AlertEngine;
import org.tool.model.AlertEvent;
import org.tool.model.AlertRule;
import org.tool.model.ProcessInfo;
import org.tool.model.SystemSnapshot;
import org.tool.process.ProcessMonitor;
import org.tool.process.ProcessTerminator;
import org.tool.sampler.MetricsSampler;
import org.tool.storage.SettingsRepository;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import static org.tool.sampler.MetricsSampler.HISTORY;
import static org.tool.sampler.MetricsSampler.METRICS;

/**
 * The main window: dashboard tiles with live graphs, the process list, alert rules and settings, plus the
 * banner and footer. It only ever DRAWS: new readings arrive through {@link #onSnapshot} (once per frame, on the
 * JavaFX thread) and the process list is read from {@link ProcessMonitor}'s cache, so the UI never asks the OS.
 */
public class DashboardController implements MetricsSampler.SnapshotListener {

    /** What the dashboard asks the application to do; the application owns the windows and the theme. */
    public interface Host {
        void showCompact();

        void applyTheme(boolean night);

        void compactMetricsChanged(Set<String> metrics);
    }

    private static final PseudoClass WARN = PseudoClass.getPseudoClass("warn");
    private static final PseudoClass ON = PseudoClass.getPseudoClass("on");
    private static final PseudoClass ELEVATED = PseudoClass.getPseudoClass("elevated");   // getting high, no alert yet
    private static final PseudoClass ALERT = PseudoClass.getPseudoClass("alert");
    private static final int WARN_AFTER_SECS = 30;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final Stage stage;
    private final MetricsSampler sampler;
    private final AlertEngine alertEngine;
    private final ProcessMonitor processMonitor;
    private final ProcessTerminator terminator;
    private final SettingsRepository repository;
    private final Host host;
    private final VBox root;

    private boolean night = true;                 // true = dark (default), false = light (beige)
    private final Set<String> compactMetrics = new HashSet<>();   // which metrics the compact widget shows
    private Button themeBtn;
    private ToggleButton bDash, bProc, bAlert, bSet;
    private Label alertTileText;
    private Label topProcName;
    private Label topProcPid;
    private Label topProcSub;
    private int ruleTotal;
    private int ruleActive;
    private HBox banner;
    private Label bannerText;
    private Label bannerChip;
    private static final double RULE_ROW_H = 38;        // matches -fx-cell-size in argus.css
    private static final int MAX_RULE_ROWS = 7;         // beyond this the rules table scrolls
    private final DoubleProperty ruleHeaderH = new SimpleDoubleProperty(32);
    private final Map<String, Integer> elevatedSecs = new LinkedHashMap<>();   // consecutive samples each metric has been high
    private String bannerKey = "";
    private String bannerDismissedKey = "";
    private Label footerLeft;
    private final TextField procFilter = new TextField();
    private final Label procCount = new Label();

    private final Map<String, MetricCard> cards = new LinkedHashMap<>();
    private final TableView<ProcessInfo> procTable = new TableView<>();
    private Button killButton;             // the TERMINATE button; disabled while a process is being stopped
    private final TableView<AlertRule> ruleTable = new TableView<>();
    private final ListView<String> logView = new ListView<>();
    private boolean sortByCpu = true;
    private int selectedPid = -1;          // process the user picked; stays selected until it exits or they deselect it
    private boolean refreshingTable;       // true while the table is rebuilt in code, so that is not mistaken for a click

    private SystemSnapshot lastSnapshot;
    private long shownProcVersion;

    public DashboardController(Stage stage, MetricsSampler sampler, AlertEngine alertEngine, ProcessMonitor processMonitor,
                               ProcessTerminator terminator, SettingsRepository repository,
                               Set<String> compactMetrics, Host host) {
        this.stage = stage;
        this.sampler = sampler;
        this.alertEngine = alertEngine;
        this.processMonitor = processMonitor;
        this.terminator = terminator;
        this.repository = repository;
        this.host = host;
        this.compactMetrics.addAll(compactMetrics);
        this.root = build();
    }

    /** The whole window content; the application puts it in a Scene. */
    public VBox getRoot() {
        return root;
    }

    // ---------------------------------------------------------------- building the window

    private VBox build() {
        // ---- header with navigation
        ToggleGroup nav = new ToggleGroup();
        bDash = navButton("Dashboard", nav);
        bProc = navButton("Processes", nav);
        bAlert = navButton("Alerts", nav);
        bSet = navButton("Settings", nav);
        bDash.setSelected(true);
        keepOneSelected(nav);

        HBox logo = Theme.buildLogo(false);
        logo.setPadding(new Insets(12, 28, 0, 24));      // top padding nudges the logo lower in the header
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
        compactBtn.setOnAction(e -> host.showCompact());
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        themeBtn = new Button("LIGHT MODE");
        themeBtn.getStyleClass().add("btn");
        themeBtn.setOnAction(e -> host.applyTheme(!night));
        HBox navBox = new HBox(6, bDash, bProc, bAlert, bSet);
        navBox.setAlignment(Pos.CENTER_LEFT);
        HBox right = new HBox(12, status, themeBtn, compactBtn);
        right.setAlignment(Pos.CENTER_RIGHT);
        HBox header = new HBox(logo, navBox, spacer, right);
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
        return rootBox;
    }

    // ---------------------------------------------------------------- new data arrives

    /** Draws the newest snapshot. Called once per frame, and only when a new snapshot has arrived. */
    @Override
    public void onSnapshot(SystemSnapshot s) {
        lastSnapshot = s;
        Set<String> firing = alertEngine.firing();
        if (stage.isShowing()) {                // no point redrawing charts nobody can see
            for (String m : METRICS) {
                cards.get(m).draw(sampler.history(m).toArray(), SystemSnapshot.percentText(s.value(m)), detailText(m, s),
                        firing.contains(m), s.isElevated(m));
            }
            String at = LocalTime.ofInstant(Instant.ofEpochMilli(s.timestamp()), ZoneId.systemDefault()).format(TIME);
            footerLeft.setText("LAST SAMPLE " + at + " \u00b7 EVERY 1S \u00b7 " + HISTORY
                    + "S HISTORY \u00b7 UP " + fmtUptime(s.uptimeSecs()));
        }
        trackElevated("CPU", s.cpu());
        trackElevated("MEMORY", s.memory());
        trackElevated("DISK", s.disk());
        updateBanner(firing, s);
    }

    private String detailText(String metric, SystemSnapshot s) {
        return switch (metric) {
            case "CPU" -> s.cpu() < 0 ? "N/A" : sampler.cpuSummary();
            case "MEMORY" -> s.memText();
            case "DISK" -> s.diskText();
            default -> s.batText();
        };
    }

    /** Call once per frame: redraws the process views when the monitor has a newer process list. */
    public void pollProcesses() {
        long v = processMonitor.version();
        if (v == shownProcVersion) return;
        shownProcVersion = v;
        if (stage.isShowing()) {
            updateTopProcess();
            refreshProcTable(false);
        }
    }

    // ---------------------------------------------------------------- theme

    /** Called by the application whenever the theme changes (the application also saves the choice). */
    public void setNight(boolean night) {
        this.night = night;
        Theme.setNight(root, night);
        themeBtn.setText(night ? "LIGHT MODE" : "DARK MODE");
    }

    private void styleDialog(Alert a) {
        a.getDialogPane().getStylesheets().add(Theme.STYLESHEET);
        if (night) a.getDialogPane().getStyleClass().add("night");
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
    private void updateBanner(Set<String> firing, SystemSnapshot s) {
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
                        && (worst == null || s.value(m) > s.value(worst))) worst = m;
            }
            if (worst != null) {
                key = "W:" + worst;
                text = String.format("%s at %.0f%% for %s.%s", titleCase(worst), s.value(worst),
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
        return heaviest(mem ? "MEMORY" : "CPU")
                .map(p -> " Top cause: " + p.name() + " ("
                        + (mem ? fmtMem(p.memory()) : String.format("%.1f%% CPU", p.cpu())) + ").")
                .orElse("");
    }

    /** The most resource-hungry real program. PID 0 is Windows' idle placeholder, not a program. */
    private Optional<ProcessInfo> heaviest(String metric) {
        return processMonitor.topBy(metric, 5).stream()
                .filter(p -> p.pid() != 0 && !p.name().toLowerCase().contains("idle"))
                .findFirst();
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
        topProcPid = new Label();
        topProcPid.getStyleClass().addAll("tag", "tag-dim");        // the PID, shown as its own chip next to the name
        topProcPid.setVisible(false);
        topProcPid.setManaged(false);
        HBox nameRow = new HBox(14, topProcName, topProcPid);
        nameRow.setAlignment(Pos.CENTER_LEFT);
        topProcSub = new Label(" ");
        topProcSub.getStyleClass().add("sub");
        Button view = new Button("VIEW PROCESSES");
        view.getStyleClass().addAll("btn", "btn-solid");
        view.setOnAction(e -> bProc.fire());
        Region sp = new Region();
        VBox.setVgrow(sp, Priority.ALWAYS);
        VBox tile = new VBox(8, eyebrow, nameRow, topProcSub, view, sp);
        tile.getStyleClass().add("tile");
        VBox.setMargin(view, new Insets(6, 0, 0, 0));
        return tile;
    }

    private void updateTopProcess() {
        heaviest("CPU").ifPresent(p -> {
            topProcName.setText(p.name());
            topProcPid.setText("PID " + p.pid());
            topProcPid.setVisible(true);
            topProcPid.setManaged(true);
            topProcSub.setText(String.format("%.1f%% CPU \u00b7 %s", p.cpu(), fmtMem(p.memory())).toUpperCase());
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
        kill.setOnAction(e -> onKillClicked());
        killButton = kill;
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

        procTable.getColumns().add(col("NAME", ProcessInfo::name, 300));
        TableColumn<ProcessInfo, String> pidCol = col("PID", p -> String.valueOf(p.pid()), 100);
        TableColumn<ProcessInfo, String> cpuCol = col("CPU", p -> String.format("%.1f%%", p.cpu()), 100);
        TableColumn<ProcessInfo, String> memCol = col("MEMORY", p -> fmtMem(p.memory()), 120);
        for (TableColumn<ProcessInfo, String> c : List.of(pidCol, cpuCol, memCol)) {
            c.setStyle("-fx-alignment: CENTER-RIGHT;");
        }
        // The cells are right-aligned, so the headers of those columns must be too, or each title floats
        // away from its numbers. Header nodes only exist once the table has a skin.
        procTable.skinProperty().addListener((o, a, b) -> Platform.runLater(
                () -> rightAlignHeaders(procTable, Set.of("PID", "CPU", "MEMORY"))));
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
            protected void updateItem(ProcessInfo p, boolean empty) {
                super.updateItem(p, empty);
                if (empty || p == null || p.pid() != 0) {
                    setTooltip(null);
                    return;
                }
                SystemSnapshot s = lastSnapshot;
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
        // ranked by the monitor, biggest first, for whichever column the user sorts by
        List<ProcessInfo> ranked = processMonitor.topBy(sortByCpu ? "CPU" : "MEMORY", Integer.MAX_VALUE);
        Map<Integer, ProcessInfo> byPid = new HashMap<>();
        for (ProcessInfo p : ranked) byPid.put(p.pid(), p);
        if (selectedPid != -1 && !byPid.containsKey(selectedPid)) selectedPid = -1;      // it exited

        String needle = procFilter.getText() == null ? "" : procFilter.getText().trim().toLowerCase();
        if (selectedPid != -1 && !needle.isEmpty() && !byPid.get(selectedPid).name().toLowerCase().contains(needle)) {
            selectedPid = -1;                                                              // filtered out on purpose
        }

        List<ProcessInfo> matching = ranked.stream()
                .filter(p -> needle.isEmpty() || p.name().toLowerCase().contains(needle))
                .toList();

        boolean locked = selectedPid != -1 && !resort && !procTable.getItems().isEmpty();
        List<ProcessInfo> shown = new ArrayList<>();
        if (locked) {
            for (ProcessInfo old : procTable.getItems()) {                // same order, fresh numbers
                ProcessInfo cur = byPid.get(old.pid());
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
                for (ProcessInfo p : shown) {
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

    private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase().startsWith("windows");

    /** The Terminate button: safety checks, confirmation, then the kill on a background thread. */
    public void onKillClicked() {
        ProcessInfo p = procTable.getSelectionModel().getSelectedItem();
        if (p == null) {
            info("Select a process first.", Alert.AlertType.INFORMATION);
            return;
        }

        // Safety checks BEFORE asking: protected / unreadable / recycled-PID processes never reach the dialog.
        ProcessTerminator.Verdict first = terminator.check(p);
        if (!first.allowed()) {
            info(first.reason(), Alert.AlertType.WARNING);
            return;
        }

        // Windows ends a process at once. Linux and macOS ask it to close first and only then force it, so offer
        // a "Force kill" choice there for programs the user already knows are stuck.
        ButtonType forceKill = new ButtonType("Force kill", ButtonBar.ButtonData.OTHER);
        String who = p.name() + " (PID " + p.pid() + ")\n";
        Alert confirm;
        if (WINDOWS) {
            confirm = new Alert(Alert.AlertType.CONFIRMATION,
                    who + "Unsaved data in this app will be lost.", ButtonType.CANCEL, ButtonType.OK);
        } else {
            confirm = new Alert(Alert.AlertType.CONFIRMATION,
                    who + "Argus asks the app to close first, and force-closes it if it is still running after "
                            + ProcessTerminator.GRACE_SECONDS + " seconds.\nUnsaved data in this app may be lost.\n\n"
                            + "\"Force kill\" skips the wait and ends it immediately.",
                    ButtonType.CANCEL, ButtonType.OK, forceKill);
        }
        confirm.setTitle("Terminate process?");
        confirm.setHeaderText("TERMINATE PROCESS?");
        styleDialog(confirm);
        Optional<ButtonType> answer = confirm.showAndWait();
        if (answer.isEmpty() || answer.get() == ButtonType.CANCEL) return;
        boolean force = answer.get() == forceKill;

        // Re-check: the process may have exited (and its PID been reused) while the dialog was open.
        ProcessTerminator.Verdict verdict = terminator.check(p);
        if (!verdict.allowed()) {
            info(verdict.reason(), Alert.AlertType.WARNING);
            return;
        }

        // Kill and wait on a background thread so the UI never freezes. The button shows "STOPPING..." meanwhile
        // (a graceful stop can take several seconds) and cannot be pressed twice.
        setKillBusy(true);
        Thread worker = new Thread(() -> {
            ProcessTerminator.Result result;
            try {
                result = terminator.terminate(p, force);
            } catch (RuntimeException e) {
                result = new ProcessTerminator.Result(false, "Could not terminate " + p.name() + ": " + e.getMessage());
            }
            ProcessTerminator.Result done = result;
            Platform.runLater(() -> {
                setKillBusy(false);
                if (done.success()) {
                    repository.saveEvent(AlertEvent.now((force ? "Force-killed " : "Terminated ")
                            + p.name() + " (PID " + p.pid() + ")"));
                    refreshEvents();
                } else {
                    info(done.message(), Alert.AlertType.ERROR);
                }
            });
        }, "argus-terminate");
        worker.setDaemon(true);
        worker.start();
    }

    private void setKillBusy(boolean busy) {
        if (killButton == null) return;
        killButton.setDisable(busy);
        killButton.setText(busy ? "STOPPING..." : "TERMINATE");
    }

    // ---------------------------------------------------------------- alerts

    private VBox buildAlerts() {
        ruleTable.getColumns().add(col("METRIC", AlertRule::metric, 160));
        ruleTable.getColumns().add(col("CONDITION", r -> r.op() + " " + String.format("%.0f", r.threshold()) + "%", 140));
        ruleTable.getColumns().add(col("DURATION", r -> r.seconds() + " s", 120));
        TableColumn<AlertRule, String> enabledCol = col("ENABLED", r -> "", 100);
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
        ruleTable.setPlaceholder(new Label("No rules yet"));
        ruleTable.setFixedCellSize(RULE_ROW_H);
        // Height = header + one row per rule (min 1 row, max MAX_RULE_ROWS), so every rule is visible without scrolling.
        ruleTable.prefHeightProperty().bind(Bindings.createDoubleBinding(
                () -> Math.min(Math.max(ruleTable.getItems().size(), 1), MAX_RULE_ROWS) * RULE_ROW_H
                        + ruleHeaderH.get() + 2,
                ruleTable.getItems(), ruleHeaderH));
        ruleTable.minHeightProperty().bind(ruleTable.prefHeightProperty());
        ruleTable.maxHeightProperty().bind(ruleTable.prefHeightProperty());
        ruleTable.skinProperty().addListener((o, a, b) -> Platform.runLater(() -> {   // measure the real header height
            Node h = ruleTable.lookup(".column-header-background");
            if (h instanceof Region r) {
                if (r.getHeight() > 0) ruleHeaderH.set(r.getHeight());
                r.heightProperty().addListener((oo, x, n) -> { if (n.doubleValue() > 0) ruleHeaderH.set(n.doubleValue()); });
            }
        }));

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
                List<AlertRule> all = new ArrayList<>(repository.loadRules());
                all.add(new AlertRule(0, metric.getValue(), op.getValue(), t, s, true));
                repository.saveRules(all);
                threshold.clear();
                seconds.clear();
                refreshRules();
            } catch (NumberFormatException ex) {
                info("Limit must be 0-100 and seconds must be 1 or more.", Alert.AlertType.WARNING);
            }
        });

        Button delete = new Button("DELETE");
        delete.getStyleClass().addAll("btn", "btn-danger");
        delete.setOnAction(e -> {
            AlertRule r = ruleTable.getSelectionModel().getSelectedItem();
            if (r != null) {
                List<AlertRule> all = new ArrayList<>(repository.loadRules());
                all.removeIf(x -> x.id() == r.id());
                repository.saveRules(all);
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
    private StackPane switchFor(AlertRule r) {
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
        sw.setOnMouseClicked(e -> setRuleEnabled(r, !r.enabled()));
        return sw;
    }

    private void setRuleEnabled(AlertRule rule, boolean enabled) {
        List<AlertRule> all = new ArrayList<>(repository.loadRules());
        all.replaceAll(x -> x.id() == rule.id()
                ? new AlertRule(x.id(), x.metric(), x.op(), x.threshold(), x.seconds(), enabled) : x);
        repository.saveRules(all);
        refreshRules();
    }

    /** Reloads the rules from storage into the table and hands them to the alert engine. */
    public void refreshRules() {
        List<AlertRule> rules = repository.loadRules();
        ruleTable.getItems().setAll(rules);
        alertEngine.setRules(rules);
        ruleTotal = rules.size();
        ruleActive = (int) rules.stream().filter(AlertRule::enabled).count();
    }

    /** Reloads the "Recent triggers" list. */
    public void refreshEvents() {
        logView.getItems().setAll(repository.recentEvents(50).stream().map(AlertEvent::display).toList());
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
                host.compactMetricsChanged(new HashSet<>(compactMetrics));
            });
            boxes.getChildren().add(cb);
        }
        Label note = new Label("Your choices and theme are saved automatically and restored on the next start.");
        note.getStyleClass().add("dim");

        VBox page = new VBox(14, title, hint, boxes, note);
        page.setPadding(new Insets(20));
        return page;
    }

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

    /** Right-aligns the header text of the named columns, to sit over right-aligned cells. */
    private static void rightAlignHeaders(TableView<?> table, Set<String> titles) {
        for (Node header : table.lookupAll(".column-header")) {
            if (header.lookup(".label") instanceof Label title && titles.contains(title.getText())) {
                title.setStyle("-fx-alignment: CENTER-RIGHT;");
            }
        }
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
}
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
import javafx.scene.Group;
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
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.SVGPath;
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

/** Argus - lightweight system monitor. Hacker-terminal theme. */
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
    private boolean dark = false;                 // false = cream (default), true = night
    private VBox compactRows;
    private final Map<String, HBox> compactRowNodes = new LinkedHashMap<>();
    private final Set<String> compactMetrics = new HashSet<>();   // which metrics the compact widget shows
    private Button themeBtn;
    private Label uptimeLabel;
    private ToggleButton bDash, bProc, bAlert, bSet;
    private Label alertTileText;
    private Label topProcName;
    private Label topProcSub;
    private int ruleTotal;
    private int ruleActive;
    private HBox banner;
    private Label bannerText;
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
        Application.setUserAgentStylesheet(new PrimerLight().getUserAgentStylesheet());   // cream is the default look
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
        bDash = navButton("Dashboard", "dashboard", nav);
        bProc = navButton("Processes", "processes", nav);
        bAlert = navButton("Alerts", "alerts", nav);
        bSet = navButton("Settings", "settings", nav);
        bDash.setSelected(true);
        keepOneSelected(nav);

        ImageView mark = new ImageView(new Image(Main.class.getResource("/icons/app_128.png").toExternalForm()));
        mark.setFitWidth(40);
        mark.setFitHeight(40);
        mark.setSmooth(true);
        Label wordmark = new Label("A\u2009R\u2009G\u2009U\u2009S");
        wordmark.getStyleClass().add("wordmark");
        Label tagline = new Label("SYSTEM MONITOR");
        tagline.getStyleClass().add("tagline");
        VBox words = new VBox(0, wordmark, tagline);
        words.setAlignment(Pos.CENTER_LEFT);
        HBox logo = new HBox(12, mark, words);
        logo.setAlignment(Pos.CENTER_LEFT);
        logo.setPadding(new Insets(0, 28, 0, 0));
        Label status = new Label("\u25CF LIVE \u00b7 1S");
        status.getStyleClass().addAll("tag", "tag-ok");
        FadeTransition pulse = new FadeTransition(Duration.seconds(1), status);
        pulse.setFromValue(1.0);
        pulse.setToValue(0.45);
        pulse.setCycleCount(Animation.INDEFINITE);
        pulse.setAutoReverse(true);
        pulse.play();
        Button compactBtn = new Button("Compact mode");
        compactBtn.getStyleClass().add("btn");
        compactBtn.setOnAction(e -> showCompact());
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        themeBtn = new Button("Night mode");
        themeBtn.getStyleClass().add("btn");
        themeBtn.setOnAction(e -> applyTheme(!dark, true));
        HBox header = new HBox(logo, bDash, bProc, bAlert, bSet, spacer, status, themeBtn, compactBtn);
        header.getStyleClass().add("header");
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPrefHeight(68);

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

        Scene scene = new Scene(rootBox, 1100, 700);
        scene.getStylesheets().add(css);
        stage.setTitle("Argus");
        stage.setScene(scene);
        stage.setMinWidth(960);
        stage.setMinHeight(600);

        buildCompact();
        applyTheme("night".equals(db.getSetting("theme", "cream")), false);   // restore saved theme
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
                        refreshProcTable();
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
        update("CPU", s.cpu(), pct(s.cpu()),
                s.cpu() < 0 ? "N/A" : r.firing().contains("CPU") ? "\u26A0 ALERT: limit exceeded" : "normal", r);
        update("MEMORY", s.mem(), pct(s.mem()), s.memText(), r);
        update("DISK", s.disk(), pct(s.disk()), s.diskText(), r);
        update("BATTERY", s.battery(), pct(s.battery()), s.batText(), r);
        if (stage.isShowing()) {
            uptimeLabel.setText("UP " + fmtUptime(s.uptimeSecs()));
            footerLeft.setText("LAST SAMPLE " + LocalTime.now().format(TIME) + " \u00b7 EVERY 1S \u00b7 " + HISTORY + "S HISTORY");
        }
        updateBanner(r.firing());
    }

    /** Red banner across the top while any alert rule is firing; dismissable until the set of firing metrics changes. */
    private void updateBanner(Set<String> firing) {
        java.util.TreeSet<String> names = new java.util.TreeSet<>(firing);
        bannerKey = String.join(",", names);
        if (names.isEmpty()) bannerDismissedKey = "";
        boolean show = !names.isEmpty() && !bannerKey.equals(bannerDismissedKey);
        if (show) {
            bannerText.setText(String.join(", ", names)
                    + (names.size() == 1 ? " has crossed its alert limit" : " have crossed their alert limits"));
        }
        banner.setVisible(show);
        banner.setManaged(show);
        updateAlertTile(names);
    }

    private void buildBanner() {
        Label chip = new Label("ALERT");
        chip.getStyleClass().addAll("tag", "tag-solid-bad");
        bannerText = new Label();
        Region sp = new Region();
        HBox.setHgrow(sp, Priority.ALWAYS);
        Button close = new Button("\u2715");
        close.getStyleClass().add("btn");
        close.setOnAction(e -> {
            bannerDismissedKey = bannerKey;
            banner.setVisible(false);
            banner.setManaged(false);
        });
        banner = new HBox(14, icon("warning", 28), chip, bannerText, sp, close);
        banner.getStyleClass().add("banner");
        banner.setVisible(false);
        banner.setManaged(false);
        VBox.setMargin(banner, new Insets(14, 20, 0, 20));
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
                    : elevated ? "-fx-accent: -argus-amber;" : "-fx-accent: -argus-accent;");
            compactLabels.get(metric).setText(valueText);
        }
    }

    private static String pct(double v) {
        return v < 0 ? "N/A" : String.format("%.0f%%", v);
    }

    // ---------------------------------------------------------------- dashboard

    /** One metric card: title, big value, rolling line chart (ring buffer of the last 60 samples). */
    private final class MetricCard {
        final String metric;
        final Label num = new Label("--");
        final Label unit = new Label();
        final Label sub = new Label(" ");
        final Label tag = new Label("--");
        final XYChart.Series<Number, Number> series = new XYChart.Series<>();
        final VBox box;

        MetricCard(String title, String metric) {
            this.metric = metric;
            NumberAxis x = new NumberAxis(0, HISTORY - 1, 1);
            NumberAxis y = new NumberAxis(0, 100, 50);          // scale: 0 / 50 / 100 %
            x.setTickLabelsVisible(false);
            x.setTickMarkVisible(false);
            x.setMinorTickVisible(false);
            x.setOpacity(0);
            y.setTickMarkVisible(false);
            y.setMinorTickVisible(false);
            AreaChart<Number, Number> chart = new AreaChart<>(x, y);
            chart.setCreateSymbols(false);
            chart.setLegendVisible(false);
            chart.setAnimated(false);
            chart.setHorizontalGridLinesVisible(true);
            chart.setVerticalGridLinesVisible(false);
            chart.getData().add(series);
            chart.setMinHeight(70);
            VBox.setVgrow(chart, Priority.ALWAYS);

            Region sp = new Region();
            HBox.setHgrow(sp, Priority.ALWAYS);
            tag.getStyleClass().addAll("tag", "tag-dim");
            HBox top = new HBox(icon(iconFor(metric), 28), sp, tag);
            top.setAlignment(Pos.CENTER_LEFT);

            Label name = new Label(title);
            name.getStyleClass().add("tile-title");
            VBox.setMargin(name, new Insets(10, 0, 0, 0));
            sub.getStyleClass().add("sub");
            sub.setWrapText(true);
            sub.setMinHeight(34);
            sub.setAlignment(Pos.TOP_LEFT);
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
            boolean percent = valueText.endsWith("%");
            num.setText(percent ? valueText.substring(0, valueText.length() - 1) : valueText);
            unit.setText(percent ? "%" : "");
            sub.setText(subText);
            box.pseudoClassStateChanged(WARN, firing);
            box.pseudoClassStateChanged(ELEVATED, elevated && !firing);

            boolean na = valueText.equals("N/A");
            tag.setText(firing ? "ALERT" : elevated ? (metric.equals("BATTERY") ? "LOW" : "ELEVATED") : na ? "N/A" : "NORMAL");
            tag.getStyleClass().setAll("tag", firing ? "tag-bad" : elevated ? "tag-warn" : na ? "tag-dim" : "tag-ok");
        }
    }

    private VBox buildDashboard() {
        GridPane grid = new GridPane();
        grid.setHgap(14);
        grid.setVgap(14);
        grid.setPadding(new Insets(14, 24, 22, 24));
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
            MetricCard card = new MetricCard(tileTitle(m), m);
            cards.put(m, card);
            grid.add(card.box, col++, 0);
        }
        grid.add(buildAlertTile(), 0, 1, 2, 1);
        grid.add(buildTopProcessTile(), 2, 1, 2, 1);

        Label hw = new Label(sampler.hardwareSummary());
        hw.getStyleClass().add("dim");
        uptimeLabel = new Label("UP --");
        uptimeLabel.getStyleClass().add("dim");
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        HBox summary = new HBox(hw, gap, uptimeLabel);
        summary.setPadding(new Insets(14, 28, 0, 28));
        VBox page = new VBox(summary, grid);
        VBox.setVgrow(grid, Priority.ALWAYS);
        return page;
    }

    private static String tileTitle(String metric) {
        return switch (metric) {
            case "CPU" -> "CPU load";
            case "MEMORY" -> "Memory";
            case "DISK" -> "Hard disk space";
            default -> "Battery";
        };
    }

    private VBox buildAlertTile() {
        Label title = new Label("Alert rules");
        title.getStyleClass().add("tile-title");
        alertTileText = new Label("Loading rules...");
        alertTileText.getStyleClass().add("sub");
        alertTileText.setWrapText(true);
        Button manage = new Button("Manage alerts");
        manage.getStyleClass().addAll("btn", "btn-accent");
        manage.setOnAction(e -> bAlert.fire());
        Region sp = new Region();
        VBox.setVgrow(sp, Priority.ALWAYS);
        HBox row = new HBox(manage);
        row.setAlignment(Pos.CENTER_RIGHT);
        VBox tile = new VBox(8, title, alertTileText, sp, row);
        tile.getStyleClass().add("tile");
        return tile;
    }

    private VBox buildTopProcessTile() {
        Label title = new Label("Heaviest process");
        title.getStyleClass().add("tile-title");
        topProcName = new Label("Waiting for data...");
        topProcName.getStyleClass().add("tile-big");
        topProcSub = new Label(" ");
        topProcSub.getStyleClass().add("sub");
        Button view = new Button("View processes");
        view.getStyleClass().addAll("btn", "btn-accent");
        view.setOnAction(e -> bProc.fire());
        Region sp = new Region();
        VBox.setVgrow(sp, Priority.ALWAYS);
        HBox row = new HBox(view);
        row.setAlignment(Pos.CENTER_RIGHT);
        VBox tile = new VBox(8, title, topProcName, topProcSub, sp, row);
        tile.getStyleClass().add("tile");
        return tile;
    }

    private void updateTopProcess() {
        lastProcs.stream()
                .filter(p -> p.pid() != 0 && !p.name().toLowerCase().contains("idle"))
                .max(Comparator.comparingDouble(Sampler.Proc::cpu))
                .ifPresent(p -> {
                    topProcName.setText(p.name());
                    topProcSub.setText(String.format("%.1f%% CPU \u00b7 %s memory \u00b7 PID %d",
                            p.cpu(), fmtMem(p.memBytes()), p.pid()));
                });
    }

    private void updateAlertTile(Set<String> firing) {
        if (alertTileText == null) return;
        if (!firing.isEmpty()) {
            alertTileText.setText(String.join(", ", firing)
                    + (firing.size() == 1 ? " is over its limit right now." : " are over their limits right now."));
        } else if (ruleActive == 0) {
            alertTileText.setText("No alert rules are switched on. Turn some on to get warned early.");
        } else {
            alertTileText.setText("All clear. " + ruleActive + " of " + ruleTotal + " rules are watching your system.");
        }
    }

    // ---------------------------------------------------------------- processes

    private VBox buildProcesses() {
        ToggleGroup sort = new ToggleGroup();
        ToggleButton byCpu = sortButton("CPU", sort);
        ToggleButton byMem = sortButton("Memory", sort);
        byCpu.setSelected(true);
        keepOneSelected(sort);
        sort.selectedToggleProperty().addListener((o, a, b) -> {
            sortByCpu = byCpu.isSelected();
            refreshProcTable();
        });

        Button kill = new Button("Terminate");
        kill.getStyleClass().addAll("btn", "btn-danger");
        kill.setGraphic(icon("close", 14));
        kill.setGraphicTextGap(8);
        kill.setOnAction(e -> terminateSelected());
        Region sp = new Region();
        HBox.setHgrow(sp, Priority.ALWAYS);
        Label sortLbl = new Label("Sort by");
        sortLbl.getStyleClass().add("dim");
        procFilter.setPromptText("filter by name...");
        procFilter.setPrefWidth(180);
        procFilter.textProperty().addListener((o, a, b) -> refreshProcTable());
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
                        && getTableView().getItems().get(getIndex()).cpu() >= 50) {
                    getStyleClass().add("hot");
                }
            }
        });
        procTable.getColumns().addAll(pidCol, cpuCol, memCol);
        procTable.setPlaceholder(new Label("No matching processes"));
        procTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        VBox.setVgrow(procTable, Priority.ALWAYS);

        VBox page = new VBox(14, bar, procTable);
        page.setPadding(new Insets(20));
        return page;
    }

    private void refreshProcTable() {
        Sampler.Proc selected = procTable.getSelectionModel().getSelectedItem();
        Comparator<Sampler.Proc> cmp = sortByCpu
                ? Comparator.comparingDouble(Sampler.Proc::cpu).reversed()
                : Comparator.comparingLong(Sampler.Proc::memBytes).reversed();
        String needle = procFilter.getText() == null ? "" : procFilter.getText().trim().toLowerCase();
        List<Sampler.Proc> matching = lastProcs.stream()
                .filter(p -> needle.isEmpty() || p.name().toLowerCase().contains(needle))
                .sorted(cmp).toList();
        List<Sampler.Proc> top = matching.stream().limit(25).toList();
        procCount.setText("showing " + top.size() + " of " + matching.size());
        procTable.getItems().setAll(top);
        if (selected != null) {                       // keep the selection across refreshes
            for (Sampler.Proc p : top) {
                if (p.pid() == selected.pid()) {
                    procTable.getSelectionModel().select(p);
                    break;
                }
            }
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
        Button add = new Button("Add rule");
        add.getStyleClass().addAll("btn", "btn-primary");
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
        Button delete = new Button("Delete");
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

    /** Pill-shaped on/off switch for one alert rule. */
    private StackPane switchFor(Rule r) {
        Circle thumb = new Circle(9, Color.WHITE);
        StackPane sw = new StackPane(thumb);
        sw.getStyleClass().add("switch");
        sw.pseudoClassStateChanged(ON, r.enabled());
        sw.setMinSize(46, 24);
        sw.setPrefSize(46, 24);
        sw.setMaxSize(46, 24);
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
        VBox box = new VBox(10);
        box.setPadding(new Insets(12));
        Label title = new Label("Argus");
        title.getStyleClass().add("logo");
        Button expand = new Button("Expand");
        expand.getStyleClass().add("btn");
        expand.setOnAction(e -> showMain());
        Region sp = new Region();
        HBox.setHgrow(sp, Priority.ALWAYS);
        compactRows = new VBox(10);
        box.getChildren().addAll(new HBox(title, sp, expand), compactRows);
        for (String m : METRICS) {
            Label name = new Label(m.substring(0, Math.min(4, m.length())));
            name.setPrefWidth(48);
            ProgressBar bar = new ProgressBar(0);
            bar.setPrefWidth(150);
            Label val = new Label("--");
            val.setPrefWidth(44);
            val.setAlignment(Pos.CENTER_RIGHT);
            compactBars.put(m, bar);
            compactLabels.put(m, val);
            HBox row = new HBox(8, icon(iconFor(m), 16), name, bar, val);
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

    /** Switches the AtlantaFX base theme and toggles the "night" palette class on every window. */
    private void applyTheme(boolean darkMode, boolean save) {
        dark = darkMode;
        Application.setUserAgentStylesheet(dark
                ? new PrimerDark().getUserAgentStylesheet()
                : new PrimerLight().getUserAgentStylesheet());
        setNight(stage.getScene().getRoot(), dark);
        setNight(compact.getScene().getRoot(), dark);
        themeBtn.setText(dark ? "Cream mode" : "Night mode");
        if (save) db.setSetting("theme", dark ? "night" : "cream");
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

    private static ToggleButton navButton(String text, String iconName, ToggleGroup g) {
        ToggleButton b = new ToggleButton(text);
        b.setGraphic(icon(iconName, 18));
        b.setGraphicTextGap(8);
        b.setToggleGroup(g);
        b.getStyleClass().add("nav");
        return b;
    }

    /** Line icons on a 24x24 grid, drawn as vector paths. Colour and stroke come from the .icon CSS rules. */
    private static final Map<String, String> ICON_PATHS = Map.ofEntries(
            Map.entry("dashboard", "M4 4h6v7H4z M14 4h6v4h-6z M14 12h6v8h-6z M4 15h6v5H4z"),
            Map.entry("processes", "M9 6h11 M9 12h11 M9 18h11 M4 6h.01 M4 12h.01 M4 18h.01"),
            Map.entry("alerts", "M6 16v-5a6 6 0 0 1 12 0v5l2 2H4l2-2z M10 21h4"),
            Map.entry("settings", "M4 8h16 M4 16h16 M9 5v6 M15 13v6"),
            Map.entry("cpu", "M7 7h10v10H7z M10 10h4v4h-4z M9 3v4 M15 3v4 M9 17v4 M15 17v4 M3 9h4 M3 15h4 M17 9h4 M17 15h4"),
            Map.entry("memory", "M2 7h20v10H2z M6 11v2 M10 11v2 M14 11v2 M18 11v2 M5 17v3 M9 17v3 M15 17v3 M19 17v3"),
            Map.entry("disk", "M3 14l3-8h12l3 8v5H3z M3 14h18 M7 17h.01 M11 17h4"),
            Map.entry("battery", "M2 7h17v10H2z M21 10v4 M6 10v4 M10 10v4"),
            Map.entry("warning", "M12 3L2 20h20z M12 10v4 M12 17h.01"),
            Map.entry("close", "M6 6l12 12 M18 6L6 18"));

    private static Node icon(String name, double size) {
        SVGPath path = new SVGPath();
        path.setContent(ICON_PATHS.get(name));
        path.getStyleClass().add("icon");
        path.setScaleX(size / 24.0);
        path.setScaleY(size / 24.0);
        return new Group(path);                 // the Group takes the scaled size into account when laying out
    }

    private static String iconFor(String metric) {
        return switch (metric) {
            case "CPU" -> "cpu";
            case "MEMORY" -> "memory";
            case "DISK" -> "disk";
            default -> "battery";
        };
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
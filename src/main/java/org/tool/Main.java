package org.tool;

import atlantafx.base.theme.PrimerDark;
import atlantafx.base.theme.PrimerLight;
import javafx.animation.AnimationTimer;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.css.PseudoClass;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.RowConstraints;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.awt.Graphics2D;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
    private static final String[] METRICS = {"CPU", "MEMORY", "DISK", "BATTERY"};

    private final Sampler sampler = new Sampler();
    private final AlertEngine engine = new AlertEngine();
    private Db db;
    private ScheduledExecutorService exec;
    private String css;

    private Stage stage;
    private boolean dark = true;
    private Button themeBtn;
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
        Application.setUserAgentStylesheet(new PrimerDark().getUserAgentStylesheet());
        Platform.setImplicitExit(false);
        css = Main.class.getResource("/argus.css").toExternalForm();
        stage = primary;

        db = new Db();
        engine.setRules(db.rules());

        // ---- header with navigation
        ToggleGroup nav = new ToggleGroup();
        ToggleButton bDash = navButton("DASHBOARD", nav);
        ToggleButton bProc = navButton("PROCESSES", nav);
        ToggleButton bAlert = navButton("ALERTS", nav);
        bDash.setSelected(true);
        keepOneSelected(nav);

        Label logo = new Label("\u25C9 ARGUS");
        logo.getStyleClass().add("logo");
        Label status = new Label("\u25CF MONITORING \u00b7 1s");
        status.getStyleClass().add("dim");
        Button compactBtn = new Button("COMPACT");
        compactBtn.getStyleClass().add("btn");
        compactBtn.setOnAction(e -> showCompact());
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        themeBtn = new Button("LIGHT MODE");
        themeBtn.getStyleClass().add("btn");
        themeBtn.setOnAction(e -> applyTheme(!dark));
        HBox header = new HBox(logo, bDash, bProc, bAlert, spacer, status, themeBtn, compactBtn);
        header.getStyleClass().add("header");
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPrefHeight(48);

        // ---- pages
        VBox dashboard = buildDashboard();
        VBox processes = buildProcesses();
        VBox alerts = buildAlerts();
        StackPane content = new StackPane(dashboard, processes, alerts);
        processes.setVisible(false);
        alerts.setVisible(false);
        bDash.setOnAction(e -> show(dashboard, processes, alerts));
        bProc.setOnAction(e -> show(processes, dashboard, alerts));
        bAlert.setOnAction(e -> show(alerts, dashboard, processes));

        VBox rootBox = new VBox(header, content);
        VBox.setVgrow(content, Priority.ALWAYS);

        Scene scene = new Scene(rootBox, 1000, 640);
        scene.getStylesheets().add(css);
        stage.setTitle("Argus");
        stage.setScene(scene);
        stage.setMinWidth(760);
        stage.setMinHeight(520);

        buildCompact();
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
                    if (stage.isShowing()) refreshProcTable();
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
    }

    private void update(String metric, double v, String valueText, String subText, AlertEngine.Result r) {
        boolean firing = r.firing().contains(metric);
        if (stage.isShowing()) {                // no point redrawing charts nobody can see
            cards.get(metric).draw(histories.get(metric).toArray(), valueText, subText, firing);
        }
        if (compact.isShowing()) {
            compactBars.get(metric).setProgress(Math.max(v, 0) / 100.0);
            compactBars.get(metric).setStyle(firing ? "-fx-accent: -argus-warn;" : "-fx-accent: -argus-fg;");
            compactLabels.get(metric).setText(valueText);
        }
    }

    private static String pct(double v) {
        return v < 0 ? "N/A" : String.format("%.0f%%", v);
    }

    // ---------------------------------------------------------------- dashboard

    /** One metric card: title, big value, rolling line chart (ring buffer of the last 60 samples). */
    private final class MetricCard {
        final Label value = new Label("--");
        final Label sub = new Label(" ");
        final XYChart.Series<Number, Number> series = new XYChart.Series<>();
        final VBox box;

        MetricCard(String name) {
            NumberAxis x = new NumberAxis(0, HISTORY - 1, 1);
            NumberAxis y = new NumberAxis(0, 100, 25);
            for (NumberAxis a : new NumberAxis[]{x, y}) {
                a.setTickLabelsVisible(false);
                a.setTickMarkVisible(false);
                a.setMinorTickVisible(false);
                a.setOpacity(0);
            }
            LineChart<Number, Number> chart = new LineChart<>(x, y);
            chart.setCreateSymbols(false);
            chart.setLegendVisible(false);
            chart.setAnimated(false);
            chart.setHorizontalGridLinesVisible(false);
            chart.setVerticalGridLinesVisible(false);
            chart.getData().add(series);
            chart.setMinHeight(80);
            VBox.setVgrow(chart, Priority.ALWAYS);

            Label title = new Label(name);
            title.getStyleClass().add("card-name");
            value.getStyleClass().add("big");
            sub.getStyleClass().addAll("sub", "dim");
            Region sp = new Region();
            HBox.setHgrow(sp, Priority.ALWAYS);
            box = new VBox(new HBox(title, sp, value), chart, sub);
            box.getStyleClass().add("card");
        }

        /** data = ring-buffer contents, oldest first. */
        void draw(double[] data, String valueText, String subText, boolean firing) {
            List<XYChart.Data<Number, Number>> pts = new ArrayList<>(data.length);
            for (int i = 0; i < data.length; i++) pts.add(new XYChart.Data<Number, Number>(i, data[i]));
            series.getData().setAll(pts);
            value.setText(valueText);
            sub.setText(subText);
            box.pseudoClassStateChanged(WARN, firing);
        }
    }

    private VBox buildDashboard() {
        GridPane grid = new GridPane();
        grid.setHgap(16);
        grid.setVgap(16);
        grid.setPadding(new Insets(20));
        for (int i = 0; i < 2; i++) {
            ColumnConstraints cc = new ColumnConstraints();
            cc.setPercentWidth(50);
            grid.getColumnConstraints().add(cc);
            RowConstraints rc = new RowConstraints();
            rc.setPercentHeight(50);
            grid.getRowConstraints().add(rc);
        }
        int i = 0;
        for (String m : METRICS) {
            MetricCard card = new MetricCard(m.equals("DISK") ? "DISK USAGE" : m);
            cards.put(m, card);
            grid.add(card.box, i % 2, i / 2);
            i++;
        }
        VBox page = new VBox(grid);
        VBox.setVgrow(grid, Priority.ALWAYS);
        return page;
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
            refreshProcTable();
        });

        Button kill = new Button("TERMINATE");
        kill.getStyleClass().addAll("btn", "btn-danger");
        kill.setOnAction(e -> terminateSelected());
        Region sp = new Region();
        HBox.setHgrow(sp, Priority.ALWAYS);
        Label sortLbl = new Label("SORT BY");
        sortLbl.getStyleClass().add("dim");
        HBox bar = new HBox(10, sortLbl, byCpu, byMem, sp, kill);
        bar.setAlignment(Pos.CENTER_LEFT);

        procTable.getColumns().add(col("NAME", Sampler.Proc::name, 300));
        procTable.getColumns().add(col("PID", p -> String.valueOf(p.pid()), 100));
        procTable.getColumns().add(col("CPU", p -> String.format("%.1f%%", p.cpu()), 100));
        procTable.getColumns().add(col("MEMORY", p -> fmtMem(p.memBytes()), 120));
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
        List<Sampler.Proc> top = lastProcs.stream().sorted(cmp).limit(25).toList();
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
        ruleTable.getColumns().add(col("STATUS", r -> r.enabled() ? "[ ON ]" : "[ OFF ]", 100));
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
        Button add = new Button("+ ADD RULE");
        add.getStyleClass().add("btn");
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
        HBox form = new HBox(10, metric, op, threshold, seconds, add, sp, toggle, delete);
        form.setAlignment(Pos.CENTER_LEFT);

        Label rulesTitle = new Label("ALERT RULES");
        rulesTitle.getStyleClass().add("section");
        Label logTitle = new Label("RECENT TRIGGERS");
        logTitle.getStyleClass().add("section");
        VBox.setVgrow(logView, Priority.ALWAYS);

        VBox page = new VBox(12, rulesTitle, form, ruleTable, logTitle, logView);
        page.setPadding(new Insets(20));
        return page;
    }

    private void refreshRules() {
        List<Rule> rules = db.rules();
        ruleTable.getItems().setAll(rules);
        engine.setRules(rules);
    }

    private void refreshLogs() {
        logView.getItems().setAll(db.recentLogs(50));
    }

    // ---------------------------------------------------------------- compact mode + tray

    private void buildCompact() {
        VBox box = new VBox(10);
        box.setPadding(new Insets(12));
        Label title = new Label("\u25C9 ARGUS");
        title.getStyleClass().add("logo");
        Button expand = new Button("EXPAND");
        expand.getStyleClass().add("btn");
        expand.setOnAction(e -> showMain());
        Region sp = new Region();
        HBox.setHgrow(sp, Priority.ALWAYS);
        box.getChildren().add(new HBox(title, sp, expand));
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
            HBox row = new HBox(8, name, bar, val);
            row.setAlignment(Pos.CENTER_LEFT);
            box.getChildren().add(row);
        }
        compact = new Stage();
        compact.setTitle("Argus");
        compact.setAlwaysOnTop(true);
        compact.setResizable(false);
        Scene scene = new Scene(box);
        scene.getStylesheets().add(css);
        compact.setScene(scene);
        compact.setOnCloseRequest(e -> {
            e.consume();
            showMain();
        });
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
            BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = img.createGraphics();
            g.setColor(new java.awt.Color(0x33, 0xff, 0x66));
            g.fillOval(1, 1, 14, 14);
            g.dispose();

            java.awt.MenuItem open = new java.awt.MenuItem("Show Argus");
            open.addActionListener(e -> Platform.runLater(this::showMain));
            java.awt.MenuItem exit = new java.awt.MenuItem("Exit");
            exit.addActionListener(e -> Platform.runLater(this::quit));
            PopupMenu menu = new PopupMenu();
            menu.add(open);
            menu.add(exit);

            tray = new TrayIcon(img, "Argus", menu);
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

    /** Switches the AtlantaFX base theme and toggles the "light" palette class on every window. */
    private void applyTheme(boolean darkMode) {
        dark = darkMode;
        Application.setUserAgentStylesheet(dark
                ? new PrimerDark().getUserAgentStylesheet()
                : new PrimerLight().getUserAgentStylesheet());
        setLight(stage.getScene().getRoot(), !dark);
        setLight(compact.getScene().getRoot(), !dark);
        themeBtn.setText(dark ? "LIGHT MODE" : "DARK MODE");
    }

    private static void setLight(Parent root, boolean light) {
        root.getStyleClass().remove("light");
        if (light) root.getStyleClass().add("light");
    }

    private void styleDialog(Alert a) {
        a.getDialogPane().getStylesheets().add(css);
        if (!dark) a.getDialogPane().getStyleClass().add("light");
    }

    // ---------------------------------------------------------------- small helpers

    private static void show(VBox visible, VBox... others) {
        visible.setVisible(true);
        for (VBox o : others) o.setVisible(false);
    }

    private static ToggleButton navButton(String text, ToggleGroup g) {
        ToggleButton b = new ToggleButton(text);
        b.setToggleGroup(g);
        b.getStyleClass().add("nav");
        return b;
    }

    private static ToggleButton sortButton(String text, ToggleGroup g) {
        ToggleButton b = new ToggleButton(text);
        b.setToggleGroup(g);
        b.getStyleClass().add("btn");
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
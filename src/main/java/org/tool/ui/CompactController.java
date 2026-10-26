package org.tool.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.image.Image;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.tool.alert.AlertEngine;
import org.tool.model.SystemSnapshot;
import org.tool.sampler.MetricsSampler;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.tool.sampler.MetricsSampler.METRICS;

/** The small always-on-top window: one live bar per chosen metric. */
public class CompactController implements MetricsSampler.SnapshotListener {

    private final Stage alwaysOnTop;
    private final AlertEngine alertEngine;
    private final VBox rows = new VBox(10);
    private final Map<String, HBox> rowNodes = new LinkedHashMap<>();
    private final Map<String, ProgressBar> bars = new LinkedHashMap<>();
    private final Map<String, Label> labels = new LinkedHashMap<>();
    private Set<String> visibleMetrics;

    /**
     * @param stage          the (still empty) window this controller fills
     * @param icons          window icons
     * @param visibleMetrics which metrics to show
     * @param onExpand       what the "Expand" button does (go back to the main window)
     */
    public CompactController(Stage stage, List<Image> icons, AlertEngine alertEngine,
                             Set<String> visibleMetrics, Runnable onExpand) {
        this.alwaysOnTop = stage;
        this.alertEngine = alertEngine;
        this.visibleMetrics = visibleMetrics;

        VBox box = new VBox(12);
        box.setPadding(new Insets(14));
        box.getStyleClass().add("compact-root");
        HBox title = Theme.buildLogo(true);
        Button expand = new Button("EXPAND");
        expand.getStyleClass().add("btn");
        expand.setOnAction(e -> onExpand.run());
        Region sp = new Region();
        HBox.setHgrow(sp, Priority.ALWAYS);
        HBox top = new HBox(title, sp, expand);
        top.setAlignment(Pos.CENTER_LEFT);
        box.getChildren().addAll(top, rows);

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
            bars.put(m, bar);
            labels.put(m, val);
            HBox row = new HBox(8, name, bar, val);
            row.setAlignment(Pos.CENTER_LEFT);
            rowNodes.put(m, row);
        }

        stage.setTitle("Argus");
        stage.setAlwaysOnTop(true);
        stage.setResizable(false);
        stage.getIcons().addAll(icons);
        Scene scene = new Scene(box);
        scene.getStylesheets().add(Theme.STYLESHEET);
        stage.setScene(scene);
        stage.setOnCloseRequest(e -> {
            e.consume();
            onExpand.run();
        });
        applySelection();
    }

    /** Draws the newest snapshot. Skipped while the window is hidden. */
    @Override
    public void onSnapshot(SystemSnapshot s) {
        if (!alwaysOnTop.isShowing()) return;
        Set<String> firing = alertEngine.firing();
        for (String m : METRICS) {
            double v = s.value(m);
            bars.get(m).setProgress(Math.max(v, 0) / 100.0);
            bars.get(m).setStyle(firing.contains(m) ? "-fx-accent: -argus-warn;"
                    : s.isElevated(m) ? "-fx-accent: -argus-amber;" : "-fx-accent: -argus-ok;");
            labels.get(m).setText(SystemSnapshot.percentText(v));
        }
    }

    /** Shows only the chosen metrics, always in the same order. */
    public void setVisibleMetrics(Set<String> metrics) {
        this.visibleMetrics = metrics;
        applySelection();
    }

    private void applySelection() {
        rows.getChildren().clear();
        for (String m : METRICS) {
            if (visibleMetrics.contains(m)) rows.getChildren().add(rowNodes.get(m));
        }
        if (alwaysOnTop.isShowing()) alwaysOnTop.sizeToScene();
    }

    public void setNight(boolean night) {
        Theme.setNight(alwaysOnTop.getScene().getRoot(), night);
    }

    public void show() {
        alwaysOnTop.show();
    }

    public void hide() {
        alwaysOnTop.hide();
    }
}

package org.tool.ui;

import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.layout.HBox;
import javafx.scene.shape.Circle;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;

import java.io.InputStream;

/** Shared look of both windows: the stylesheet, the night-palette switch and the "Argus." logo. */
public final class Theme {

    /** The one stylesheet (src/main/resources/argus.css) used by every window and dialog. */
    public static final String STYLESHEET = Theme.class.getResource("/argus.css").toExternalForm();

    private Theme() {
    }

    /** The scene root carries the "night" style class while the dark palette is active. */
    public static void setNight(Parent root, boolean night) {
        root.getStyleClass().remove("night");
        if (night) root.getStyleClass().add("night");
    }

    /** The bundled logo face (src/main/resources/fonts/SpaceGrotesk-Bold.ttf) at the given size, or null if absent. */
    private static Font logoFont(double size) {
        try (InputStream in = Theme.class.getResourceAsStream("/fonts/SpaceGrotesk-Bold.ttf")) {
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
    public static HBox buildLogo(boolean small) {
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
}

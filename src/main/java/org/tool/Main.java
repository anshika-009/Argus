package org.tool;

import atlantafx.base.theme.PrimerDark;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import oshi.SystemInfo;
import oshi.hardware.CentralProcessor;

/** Setup check: if this window opens and shows your CPU name, everything is installed correctly. */
public class Main extends Application {
    @Override
    public void start(Stage stage) {
        Application.setUserAgentStylesheet(new PrimerDark().getUserAgentStylesheet());
        Platform.setImplicitExit(false);

        CentralProcessor cpu = new SystemInfo().getHardware().getProcessor();
        Label label = new Label("Argus is alive!\nCPU: " + cpu.getProcessorIdentifier().getName());

        stage.setTitle("Argus");
        stage.setScene(new Scene(new StackPane(label), 420, 200));
        stage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
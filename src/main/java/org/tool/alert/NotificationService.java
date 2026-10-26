package org.tool.alert;

import java.awt.Graphics2D;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import javax.imageio.ImageIO;

/**
 * Desktop notifications and the system-tray icon (java.awt.SystemTray). Notifications keep working while the
 * main window is hidden, which is the whole point of the tray: Argus keeps watching from there.
 */
public class NotificationService {

    private volatile TrayIcon tray;

    /**
     * Puts Argus in the system tray with a "Show Argus" / "Exit" menu. The callbacks run on an AWT thread.
     *
     * @return false if the platform has no system tray (Argus then simply runs without one)
     */
    public boolean install(Runnable onShow, Runnable onExit) {
        if (!SystemTray.isSupported()) return false;
        try {
            BufferedImage img = null;
            try (InputStream in = NotificationService.class.getResourceAsStream("/icons/app_16.png")) {
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
            open.addActionListener(e -> onShow.run());
            java.awt.MenuItem exit = new java.awt.MenuItem("Exit");
            exit.addActionListener(e -> onExit.run());
            PopupMenu menu = new PopupMenu();
            menu.add(open);
            menu.add(exit);

            TrayIcon icon = new TrayIcon(img, "Argus", menu);
            icon.setImageAutoSize(true);
            icon.addActionListener(e -> onShow.run());          // double-click
            SystemTray.getSystemTray().add(icon);
            tray = icon;
            return true;
        } catch (Exception e) {
            tray = null;                                        // run without a tray icon
            return false;
        }
    }

    public boolean isTrayAvailable() {
        return tray != null;
    }

    /** Shows a warning balloon. Does nothing when there is no tray. Safe to call from any thread. */
    public void notify(String title, String text) {
        TrayIcon icon = tray;
        if (icon != null) icon.displayMessage(title, text, TrayIcon.MessageType.WARNING);
    }

    public void remove() {
        TrayIcon icon = tray;
        if (icon != null) {
            SystemTray.getSystemTray().remove(icon);
            tray = null;
        }
    }
}

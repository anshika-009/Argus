package org.tool.alert;

import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JWindow;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsEnvironment;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.PopupMenu;
import java.awt.Rectangle;
import java.awt.SystemTray;
import java.awt.Toolkit;
import java.awt.TrayIcon;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Desktop notifications and the system-tray icon.
 *
 * <p>An alert is shown two ways: a pop-up card in the bottom-right corner of the screen (always visible, even when
 * Windows Focus Assist hides tray balloons, and it never steals keyboard focus), and the usual tray balloon as a
 * second channel. The tray also keeps Argus running while the main window is hidden.
 */
public class NotificationService {

    /** Also show the Windows tray balloon next to the pop-up card. Set to false to avoid a double notification. */
    private static final boolean TRAY_BALLOON = true;

    private static final int MAX_VISIBLE = 4;          // older cards are dismissed when more arrive
    private static final int VISIBLE_MS = 10_000;      // how long a card stays on screen
    private static final int MARGIN = 16;              // distance from the screen / taskbar edge
    private static final int GAP = 10;                 // space between stacked cards
    private static final int TEXT_WIDTH = 320;

    // matches the ALERT card on the dashboard
    private static final Color RED = new Color(0xC0, 0x39, 0x2B);
    private static final Color BACKGROUND = new Color(0xED, 0xCF, 0xC4);
    private static final Color TEXT = new Color(0x11, 0x11, 0x11);

    private volatile TrayIcon tray;
    private final List<JWindow> toasts = new ArrayList<>();      // touched only on the AWT event thread

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
                g.setColor(new Color(0x2e, 0xc4, 0xe6));
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

    /**
     * Shows an alert: a pop-up card on screen, plus a tray balloon when a tray exists.
     * Safe to call from any thread, and it works even when there is no system tray.
     */
    public void notify(String title, String text) {
        showCard(title, text);
        TrayIcon icon = tray;
        if (TRAY_BALLOON && icon != null) {
            try {
                icon.displayMessage(title, text, TrayIcon.MessageType.WARNING);
            } catch (Exception ignored) {
                // the pop-up card above already told the user
            }
        }
    }

    public void remove() {
        TrayIcon icon = tray;
        if (icon != null) {
            SystemTray.getSystemTray().remove(icon);
            tray = null;
        }
        if (!GraphicsEnvironment.isHeadless()) {
            SwingUtilities.invokeLater(() -> {
                for (JWindow w : new ArrayList<>(toasts)) w.dispose();
                toasts.clear();
            });
        }
    }

    // ---- pop-up cards -------------------------------------------------------------------------------------------

    private void showCard(String title, String text) {
        if (GraphicsEnvironment.isHeadless()) return;
        SwingUtilities.invokeLater(() -> {
            try {
                buildCard(title, text);
            } catch (Exception e) {
                e.printStackTrace();                            // never let a display problem break the sampler
            }
        });
    }

    private void buildCard(String title, String text) {
        Toolkit.getDefaultToolkit().beep();
        while (toasts.size() >= MAX_VISIBLE) dismiss(toasts.get(0));

        JLabel tag = new JLabel("ALERT");
        tag.setFont(new Font("Consolas", Font.BOLD, 12));
        tag.setForeground(RED);

        JLabel heading = new JLabel(title);
        heading.setFont(new Font("Segoe UI", Font.BOLD, 17));
        heading.setForeground(TEXT);

        JLabel message = new JLabel("<html><body style='width:" + TEXT_WIDTH + "px'>" + escape(text) + "</body></html>");
        message.setFont(new Font("Segoe UI", Font.PLAIN, 14));
        message.setForeground(TEXT);

        JLabel close = new JLabel("\u2715");                    // ✕
        close.setFont(new Font("Segoe UI Symbol", Font.PLAIN, 14));
        close.setForeground(RED);

        JPanel top = new JPanel(new BorderLayout());
        top.setOpaque(false);
        top.add(tag, BorderLayout.WEST);
        top.add(close, BorderLayout.EAST);
        top.setAlignmentX(Component.LEFT_ALIGNMENT);
        heading.setAlignmentX(Component.LEFT_ALIGNMENT);
        message.setAlignmentX(Component.LEFT_ALIGNMENT);

        JPanel body = new JPanel();
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.setOpaque(false);
        body.setBorder(BorderFactory.createEmptyBorder(12, 14, 14, 12));
        body.add(top);
        body.add(javax.swing.Box.createVerticalStrut(6));
        body.add(heading);
        body.add(javax.swing.Box.createVerticalStrut(4));
        body.add(message);

        JPanel strip = new JPanel();
        strip.setBackground(RED);
        strip.setPreferredSize(new Dimension(8, 1));

        JPanel card = new JPanel(new BorderLayout());
        card.setBackground(BACKGROUND);
        card.setBorder(BorderFactory.createLineBorder(RED, 1));
        card.add(strip, BorderLayout.WEST);
        card.add(body, BorderLayout.CENTER);

        JWindow window = new JWindow();
        window.setFocusableWindowState(false);                  // never steal focus from what the user is typing in
        window.setAlwaysOnTop(true);
        window.setContentPane(card);
        window.pack();

        onClick(card, () -> dismiss(window));                   // click anywhere on the card to close it
        card.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        toasts.add(window);
        layoutCards();
        window.setVisible(true);

        Timer timer = new Timer(VISIBLE_MS, e -> dismiss(window));
        timer.setRepeats(false);
        timer.start();
    }

    private void dismiss(JWindow window) {
        if (!toasts.remove(window)) return;                     // already gone
        window.dispose();
        layoutCards();
    }

    /** Stacks the cards upwards from the bottom-right corner, above the taskbar; the newest card is lowest. */
    private void layoutCards() {
        GraphicsConfiguration gc = GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getDefaultScreenDevice().getDefaultConfiguration();
        Rectangle screen = gc.getBounds();
        Insets insets = Toolkit.getDefaultToolkit().getScreenInsets(gc);   // taskbar
        int right = screen.x + screen.width - insets.right - MARGIN;
        int y = screen.y + screen.height - insets.bottom - MARGIN;
        for (int i = toasts.size() - 1; i >= 0; i--) {
            JWindow w = toasts.get(i);
            y -= w.getHeight();
            w.setLocation(right - w.getWidth(), y);
            y -= GAP;
        }
    }

    private static void onClick(Component c, Runnable action) {
        c.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                action.run();
            }
        });
        if (c instanceof Container container) {
            for (Component child : container.getComponents()) onClick(child, action);
        }
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\n", "<br>");
    }
}
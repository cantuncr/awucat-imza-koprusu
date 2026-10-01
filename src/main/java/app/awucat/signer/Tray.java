package app.awucat.signer;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Desktop;
import java.awt.Graphics2D;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.RenderingHints;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.image.BufferedImage;
import java.io.IOException;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;

/** Tray icon (or a small status window where there is no tray) so the user can see and stop the bridge. */
final class Tray {
    private Tray() {}

    static void install(String statusText, Runnable quit) {
        SwingUtilities.invokeLater(() -> {
            if (SystemTray.isSupported()) installTray(statusText, quit);
            else installWindow(statusText, quit);
        });
    }

    private static void installTray(String statusText, Runnable quit) {
        PopupMenu menu = new PopupMenu();
        MenuItem status = new MenuItem(statusText);
        status.setEnabled(false);
        menu.add(status);
        MenuItem log = new MenuItem("Günlük klasörünü aç");
        log.addActionListener(e -> openFolder());
        menu.add(log);
        menu.addSeparator();
        MenuItem exit = new MenuItem("Çıkış");
        exit.addActionListener(e -> quit.run());
        menu.add(exit);
        TrayIcon icon = new TrayIcon(icon(SystemTray.getSystemTray().getTrayIconSize().width), statusText, menu);
        icon.setImageAutoSize(true);
        icon.addActionListener(e -> JOptionPane.showMessageDialog(null, statusText,
                "AwuCat İmza Köprüsü", JOptionPane.INFORMATION_MESSAGE));
        try {
            SystemTray.getSystemTray().add(icon);
        } catch (Exception e) {
            Log.warn("Sistem tepsisi simgesi eklenemedi: " + e);
            installWindow(statusText, quit);
        }
    }

    private static void installWindow(String statusText, Runnable quit) {
        JFrame frame = new JFrame("AwuCat İmza Köprüsü");
        frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        frame.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosing(java.awt.event.WindowEvent e) { frame.setState(JFrame.ICONIFIED); }
        });
        JPanel panel = new JPanel();
        panel.add(new JLabel(statusText));
        JButton exit = new JButton("Çıkış");
        exit.addActionListener(e -> quit.run());
        panel.add(exit);
        frame.getContentPane().add(panel);
        frame.pack();
        frame.setLocationByPlatform(true);
        frame.setVisible(true);
    }

    private static void openFolder() {
        try {
            Desktop.getDesktop().open(Config.DIR.toFile());
        } catch (IOException | RuntimeException e) {
            Log.warn("Klasör açılamadı: " + e);
        }
    }

    /** A simple pen-nib glyph drawn at runtime, so the jar needs no image resources. */
    private static BufferedImage icon(int size) {
        int s = Math.max(16, size);
        BufferedImage img = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(0x1F, 0x3A, 0x5F));
        g.fillRoundRect(0, 0, s, s, s / 3, s / 3);
        g.setColor(Color.WHITE);
        g.setStroke(new BasicStroke(Math.max(1.5f, s / 9f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.drawLine(s / 4, s * 3 / 4, s * 3 / 4, s / 4);
        g.drawLine(s / 4, s * 3 / 4, s / 4 + s / 6, s * 3 / 4);
        g.dispose();
        return img;
    }
}

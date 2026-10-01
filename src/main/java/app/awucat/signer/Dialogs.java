package app.awucat.signer;

import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Toolkit;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.SwingUtilities;

/**
 * Native Swing dialogs (Turkish). All methods block the calling HTTP thread until the user answers
 * and may only be used when a display is available. PINs are returned as {@code char[]} so the
 * caller can wipe them.
 */
final class Dialogs {
    private static final String APP = "AwuCat İmza Köprüsü";

    private Dialogs() {}

    /** "Allow this site to use the signing bridge?" */
    static boolean confirmOrigin(String origin) {
        JPanel panel = form();
        row(panel, 0, "Site:", value(origin));
        row(panel, 1, "", new JLabel("Bu sitenin imza köprüsünü kullanmasına izin ver?"));
        return show(panel, "Erişim izni — " + APP, new String[] {"İzin ver", "Reddet"}, null) == 0;
    }

    /** PIN prompt for reading the card's certificates; null when cancelled. */
    static char[] askPin(String message) {
        JPasswordField pin = new JPasswordField(12);
        JPanel panel = form();
        row(panel, 0, "", new JLabel(message));
        row(panel, 1, "Kart PIN'i:", pin);
        return show(panel, "Kart PIN'i — " + APP, new String[] {"Tamam", "Vazgeç"}, pin) == 0 ? pin.getPassword() : null;
    }

    /**
     * Signing confirmation showing who wants what signed with which certificate. Returns the
     * entered PIN (empty when {@code askPin} is false or the field was left blank), or null when
     * the user cancelled.
     */
    static char[] confirmSign(String origin, String documentName, String subject, boolean askPin) {
        JPasswordField pin = new JPasswordField(12);
        JPanel panel = form();
        row(panel, 0, "", new JLabel("Bir web sitesi bir belgeyi e-imza ile imzalamak istiyor."));
        row(panel, 1, "Site:", value(origin));
        row(panel, 2, "Belge:", value(documentName));
        row(panel, 3, "Sertifika:", value(subject));
        if (askPin) row(panel, 4, "Kart PIN'i:", pin);
        else row(panel, 4, "", new JLabel("PIN istekle birlikte alındı."));
        int choice = show(panel, "İmza onayı — " + APP, new String[] {"İmzala", "Vazgeç"}, askPin ? pin : null);
        return choice == 0 ? (askPin ? pin.getPassword() : new char[0]) : null;
    }

    private static JPanel form() {
        return new JPanel(new GridBagLayout());
    }

    private static JLabel value(String text) {
        JLabel l = new JLabel(text == null || text.isEmpty() ? "—" : text);
        l.setFont(l.getFont().deriveFont(Font.BOLD));
        return l;
    }

    private static void row(JPanel panel, int y, String label, JComponent field) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridy = y;
        c.insets = new Insets(4, 4, 4, 4);
        c.anchor = GridBagConstraints.WEST;
        c.gridx = 0;
        panel.add(new JLabel(label), c);
        c.gridx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1;
        panel.add(field, c);
    }

    /** Shows a modal, always-on-top option dialog on the EDT; returns the chosen button index (-1 if closed). */
    private static int show(JComponent content, String title, String[] buttons, JComponent focus) {
        AtomicReference<Integer> result = new AtomicReference<>(-1);
        Runnable task = () -> {
            JOptionPane pane = new JOptionPane(content, JOptionPane.QUESTION_MESSAGE, JOptionPane.DEFAULT_OPTION,
                    null, buttons, buttons[0]);
            JDialog dialog = pane.createDialog(title);
            dialog.setAlwaysOnTop(true);
            if (focus != null) {
                dialog.addWindowListener(new WindowAdapter() {
                    @Override public void windowOpened(WindowEvent e) { focus.requestFocusInWindow(); }
                });
            }
            Toolkit.getDefaultToolkit().beep();
            dialog.toFront();
            dialog.setVisible(true);
            dialog.dispose();
            Object v = pane.getValue();
            for (int i = 0; i < buttons.length; i++) if (buttons[i].equals(v)) result.set(i);
        };
        try {
            if (SwingUtilities.isEventDispatchThread()) task.run();
            else SwingUtilities.invokeAndWait(task);
        } catch (Exception e) {
            Log.error("Pencere gösterilemedi", e);
        }
        return result.get();
    }
}

package client;

import ui.MainForm;

public class ClientMain {
    public static void main(String[] args) {
        javax.swing.SwingUtilities.invokeLater(() -> {
            MainForm form = new MainForm();
            form.setLocationRelativeTo(null);
            form.setVisible(true);
        });
    }
}

package ui;

import client.ChatClient;
import client.MessageHandler;

import javax.swing.*;
import java.awt.event.KeyEvent;
import java.io.File;
import java.io.FileOutputStream;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

public class MainForm extends javax.swing.JFrame {

    private ChatClient chatClient;
    private final DateTimeFormatter timeFmt = DateTimeFormatter.ofPattern("HH:mm:ss");
    private final DefaultListModel<FileItem> fileListModel = new DefaultListModel<>();
    private File pendingFileToSend;
    
    private static class FileItem {
        final String filename;
        final byte[] data;
        FileItem(String filename, byte[] data) {
            this.filename = filename; this.data = data;
        }
        @Override public String toString() { return filename; }
    }
    
    public MainForm() {
        initComponents();
        fileList.setModel(fileListModel);
        fileList.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2) { 
                    FileItem it = fileList.getSelectedValue();
                    if (it == null || it.data == null || it.data.length == 0) return;

                    JFileChooser save = new JFileChooser();
                    save.setSelectedFile(new File(it.filename));
                    int res = save.showSaveDialog(MainForm.this);
                    if (res == JFileChooser.APPROVE_OPTION) {
                        try (FileOutputStream fos = new FileOutputStream(save.getSelectedFile())) {
                            fos.write(it.data);
                        } catch (Exception ex) {
                            JOptionPane.showMessageDialog(MainForm.this, "Save failed: " + ex.getMessage(),
                                    "Error", JOptionPane.ERROR_MESSAGE);
                        }
                    }
                }
            }
        });
        
        configureTextArea();
        connectToServer();
        installEnterToSend();
    }

    private void configureTextArea() {
        taMessageDisplay.setEditable(false);
        taMessageDisplay.setLineWrap(true);
        taMessageDisplay.setWrapStyleWord(true);
    }

    private void appendMessage(String sender, String content) {
        String time = LocalTime.now().truncatedTo(ChronoUnit.SECONDS).format(timeFmt);
        String line = String.format("[%s] %s: %s", time, sender, content);
        if (taMessageDisplay.getDocument().getLength() == 0) {
            taMessageDisplay.setText(line);
        } else {
            taMessageDisplay.append("\n" + line);
        }
        taMessageDisplay.setCaretPosition(taMessageDisplay.getDocument().getLength());
    }

    private void installEnterToSend() {
        messageInputField.addKeyListener(new java.awt.event.KeyAdapter() {
            @Override public void keyPressed(java.awt.event.KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ENTER && !e.isShiftDown()) {
                    e.consume();
                    sendCurrentText();
                }
            }
        });
    }

    private String myId;

    private void connectToServer() {
        chatClient = new ChatClient("127.0.0.1", 5000);
        try {
            chatClient.connect(new MessageHandler() {
                @Override public void onText(String senderId, String text) {
                    SwingUtilities.invokeLater(() -> {
                        if (text != null && text.startsWith("SYS_ID:")) {
                            myId = text.substring("SYS_ID:".length()).trim();
                            return;
                        }
                        String display = (myId != null && myId.equals(senderId)) ? "Me" : senderId;
                        appendMessage(display, text);
                    });
                }

                @Override public void onFile(String senderId, String filename, byte[] data) {
                    SwingUtilities.invokeLater(() -> {
                        String display = (myId != null && myId.equals(senderId)) ? "Me" : senderId;
                        appendMessage(display, "[File] " + filename + " (" + data.length + " bytes)");
                        fileListModel.addElement(new FileItem(filename, data));
                        int last = fileListModel.getSize() - 1;
                        if (last >= 0) fileList.ensureIndexIsVisible(last);
                    });
                }

            });
            appendMessage("System", "Connected to server");
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "Cannot connect: " + e.getMessage(),
                    "Connection Error", JOptionPane.ERROR_MESSAGE);
        }

        this.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosing(java.awt.event.WindowEvent e) {
                if (chatClient != null) chatClient.close();
            }
        });
    }


    private void sendCurrentText() {
    String message = messageInputField.getText().trim();

    try {
        boolean sentSomething = false;

        // 1) Gửi file nếu có
        if (pendingFileToSend != null) {
            File f = pendingFileToSend;
            pendingFileToSend = null;

            chatClient.sendFile(f);
            messageInputField.setText("");
            sentSomething = true;
        }

        // 2) Gửi text nếu có
        if (!message.isBlank() && !message.startsWith("[FILE]")) {
            chatClient.sendMessage(message);
            messageInputField.setText("");
            sentSomething = true;
        }

        if (!sentSomething) {
            JOptionPane.showMessageDialog(
                this,
                "Please enter message or choose a file!",
                "Missing content",
                JOptionPane.WARNING_MESSAGE
            );
        }

        messageInputField.requestFocus();

    } catch (Exception e) {
        JOptionPane.showMessageDialog(this, "Send failed: " + e.getMessage(),
                "Error", JOptionPane.ERROR_MESSAGE);
    }
}



    /**
     * This method is called from within the constructor to initialize the form.
     * WARNING: Do NOT modify this code. The content of this method is always
     * regenerated by the Form Editor.
     */
    @SuppressWarnings("unchecked")
    
    // <editor-fold defaultstate="collapsed" desc="Generated Code">//GEN-BEGIN:initComponents
    private void initComponents() {

        jScrollPane1 = new javax.swing.JScrollPane();
        taMessageDisplay = new javax.swing.JTextArea();
        messageInputField = new javax.swing.JTextField();
        sendButton = new java.awt.Button();
        chooseFile = new javax.swing.JButton();
        jScrollPane2 = new javax.swing.JScrollPane();
        fileList = new javax.swing.JList<>();
        voiceChat = new javax.swing.JButton();

        setDefaultCloseOperation(javax.swing.WindowConstants.EXIT_ON_CLOSE);

        taMessageDisplay.setColumns(20);
        taMessageDisplay.setRows(5);
        taMessageDisplay.setEnabled(false);
        jScrollPane1.setViewportView(taMessageDisplay);

        messageInputField.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                messageInputFieldActionPerformed(evt);
            }
        });

        sendButton.setLabel("Send");
        sendButton.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                sendButtonActionPerformed(evt);
            }
        });

        chooseFile.setText("File");
        chooseFile.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                chooseFileActionPerformed(evt);
            }
        });

        jScrollPane2.setViewportView(fileList);

        voiceChat.setText("Voice");

        javax.swing.GroupLayout layout = new javax.swing.GroupLayout(getContentPane());
        getContentPane().setLayout(layout);
        layout.setHorizontalGroup(
            layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(layout.createSequentialGroup()
                .addContainerGap()
                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addGroup(layout.createSequentialGroup()
                        .addComponent(jScrollPane1, javax.swing.GroupLayout.PREFERRED_SIZE, 250, javax.swing.GroupLayout.PREFERRED_SIZE)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addComponent(jScrollPane2, javax.swing.GroupLayout.DEFAULT_SIZE, 110, Short.MAX_VALUE))
                    .addGroup(layout.createSequentialGroup()
                        .addComponent(chooseFile)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.UNRELATED)
                        .addComponent(voiceChat)
                        .addGap(0, 0, Short.MAX_VALUE))
                    .addGroup(layout.createSequentialGroup()
                        .addComponent(messageInputField, javax.swing.GroupLayout.PREFERRED_SIZE, 306, javax.swing.GroupLayout.PREFERRED_SIZE)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                        .addComponent(sendButton, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)))
                .addContainerGap())
        );
        layout.setVerticalGroup(
            layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(layout.createSequentialGroup()
                .addContainerGap()
                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING, false)
                    .addComponent(jScrollPane2, javax.swing.GroupLayout.DEFAULT_SIZE, 280, Short.MAX_VALUE)
                    .addComponent(jScrollPane1))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.TRAILING)
                    .addGroup(layout.createSequentialGroup()
                        .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.TRAILING)
                            .addComponent(chooseFile)
                            .addComponent(voiceChat, javax.swing.GroupLayout.PREFERRED_SIZE, 23, javax.swing.GroupLayout.PREFERRED_SIZE))
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addComponent(messageInputField, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
                    .addComponent(sendButton, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
                .addContainerGap(javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
        );

        pack();
    }// </editor-fold>//GEN-END:initComponents

    private void sendButtonActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_sendButtonActionPerformed
        // TODO add your handling code here:
        sendCurrentText();
    }//GEN-LAST:event_sendButtonActionPerformed

    private void chooseFileActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_chooseFileActionPerformed
        // TODO add your handling code here:
        JFileChooser fc = new JFileChooser();
        fc.setFileSelectionMode(JFileChooser.FILES_ONLY);
        int res = fc.showOpenDialog(this);
        if (res == JFileChooser.APPROVE_OPTION) {
            File file = fc.getSelectedFile();

            pendingFileToSend = file;
             messageInputField.setText("[FILE] " + file.getName());
        }
    }//GEN-LAST:event_chooseFileActionPerformed

    private void messageInputFieldActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_messageInputFieldActionPerformed
        // TODO add your handling code here:
    }//GEN-LAST:event_messageInputFieldActionPerformed

    /**
     * @param args the command line arguments
     */
    public static void main(String[] args) {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {}

        java.awt.EventQueue.invokeLater(() -> new MainForm().setVisible(true));
    }


    // Variables declaration - do not modify//GEN-BEGIN:variables
    private javax.swing.JButton chooseFile;
    private javax.swing.JList<FileItem> fileList;
    private javax.swing.JScrollPane jScrollPane1;
    private javax.swing.JScrollPane jScrollPane2;
    private javax.swing.JTextField messageInputField;
    private java.awt.Button sendButton;
    private javax.swing.JTextArea taMessageDisplay;
    private javax.swing.JButton voiceChat;
    // End of variables declaration//GEN-END:variables
}

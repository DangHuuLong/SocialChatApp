//package tools;
//
//import client.ClientConnection;
//import java.io.File;
//
//public class Uploader {
//    public static void main(String[] a) {
//        if (a.length < 5) {
//            System.out.println("Usage: java tools.Uploader <host> <port> <from> <to> <path> [mime]");
//            return;
//        }
//        String host = a[0];
//        int port    = Integer.parseInt(a[1]);
//        String from = a[2];
//        String to   = a[3];
//        File f      = new File(a[4]);
//        String mime = (a.length >= 6) ? a[5] : "application/octet-stream";
//
//        try {
//            ClientConnection conn = new ClientConnection();
//            if (!conn.connect(host, port)) {
//                System.out.println("connect fail"); return;
//            }
//            conn.startListener(System.out::println, Throwable::printStackTrace);
//            conn.login(from);
//            Thread.sleep(300);
//
//            String ack = conn.sendFileWithAck(from, to, f, mime, 30_000);
//            System.out.println("ACK = " + ack);
//
//            conn.quit();
//        } catch (Exception e) {
//            e.printStackTrace();
//        }
//    }
//}

package vn.baohanh.test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.sun.net.httpserver.HttpServer;

/**
 * Máy chủ GIẢ dùng trong kiểm thử: SMTP (nhận email) và HTTP (giả eSMS, Zalo).
 * Nhờ vậy kiểm thử được toàn bộ luồng gửi thông báo mà không cần tài khoản thật / Internet.
 */
final class MayChuGia implements AutoCloseable {
    /** Nội dung đã nhận: "EMAIL|người nhận|thân (đã giải mã)", "SMS|thân form", "ZALO|token|json". */
    final List<String> received = new CopyOnWriteArrayList<>();
    volatile boolean smtpRejectAuth = false;
    volatile boolean esmsFail = false;
    final int smtpPort;
    final int httpPort;
    private final ServerSocket smtp;
    private final HttpServer http;

    MayChuGia() throws IOException {
        smtp = new ServerSocket(0, 10, java.net.InetAddress.getLoopbackAddress());
        smtpPort = smtp.getLocalPort();
        Thread t = new Thread(() -> {
            while (!smtp.isClosed()) {
                try (Socket s = smtp.accept()) { session(s); } catch (IOException ignored) { }
            }
        }, "smtp-gia");
        t.setDaemon(true);
        t.start();

        http = HttpServer.create(new InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), 0), 0);
        httpPort = http.getAddress().getPort();
        http.createContext("/esms", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            received.add("SMS|" + java.net.URLDecoder.decode(body, StandardCharsets.UTF_8));
            String res = esmsFail ? "{\"CodeResult\":\"146\",\"ErrorMessage\":\"Sai mau brandname\"}" : "{\"CodeResult\":\"100\",\"SMSID\":\"sms-1\"}";
            reply(ex, res);
        });
        http.createContext("/zalo", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            received.add("ZALO|" + ex.getRequestHeaders().getFirst("access_token") + "|" + body);
            reply(ex, "{\"error\":0,\"message\":\"Success\",\"data\":{\"msg_id\":\"zalo-1\"}}");
        });
        http.start();
    }

    private static void reply(com.sun.net.httpserver.HttpExchange ex, String res) throws IOException {
        byte[] b = res.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(200, b.length);
        try (OutputStream o = ex.getResponseBody()) { o.write(b); }
    }

    private void session(Socket s) throws IOException {
        BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
        OutputStream out = s.getOutputStream();
        java.util.function.Consumer<String> say = l -> {
            try { out.write((l + "\r\n").getBytes(StandardCharsets.UTF_8)); out.flush(); } catch (IOException e) { throw new RuntimeException(e); }
        };
        say.accept("220 smtp-gia");
        String to = "";
        String line;
        while ((line = in.readLine()) != null) {
            String u = line.toUpperCase();
            if (u.startsWith("EHLO")) { say.accept("250-smtp-gia"); say.accept("250 AUTH LOGIN"); }
            else if (u.startsWith("AUTH LOGIN")) {
                say.accept("334 VXNlcm5hbWU6");
                in.readLine();
                say.accept("334 UGFzc3dvcmQ6");
                in.readLine();
                say.accept(smtpRejectAuth ? "535 5.7.8 Username and Password not accepted" : "235 OK");
            }
            else if (u.startsWith("MAIL FROM")) say.accept("250 OK");
            else if (u.startsWith("RCPT TO")) { to = line.replaceAll(".*<(.*)>.*", "$1"); say.accept("250 OK"); }
            else if (u.equals("DATA")) {
                say.accept("354 Go");
                StringBuilder data = new StringBuilder();
                String d;
                while ((d = in.readLine()) != null && !d.equals(".")) data.append(d).append('\n');
                String raw = data.toString();
                int split = raw.indexOf("\n\n");
                String text = new String(Base64.getMimeDecoder().decode(raw.substring(split + 2)), StandardCharsets.UTF_8);
                received.add("EMAIL|" + to + "|" + text);
                say.accept("250 Queued");
            }
            else if (u.equals("QUIT")) { say.accept("221 Bye"); return; }
            else say.accept("250 OK");
        }
    }

    List<String> of(String channel) {
        return received.stream().filter(x -> x.startsWith(channel + "|")).toList();
    }

    @Override
    public void close() throws IOException {
        smtp.close();
        http.stop(0);
    }
}

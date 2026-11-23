package vn.baohanh.notify;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * Gửi email qua SMTP – tự viết theo giao thức SMTP (RFC 5321), không cần thư viện ngoài.
 * Hỗ trợ SSL (cổng 465), STARTTLS (cổng 587) và không mã hóa (máy chủ nội bộ),
 * đăng nhập AUTH LOGIN; nội dung UTF-8 (tiêu đề và thân mã hóa base64 nên tiếng Việt không lỗi).
 *
 * Gmail: smtp.gmail.com, cổng 465 (SSL) hoặc 587 (STARTTLS), dùng "Mật khẩu ứng dụng" (App Password).
 */
public final class EmailSender {
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s<>\"]+@[^@\\s<>\"]+\\.[^@\\s<>\"]+$");
    private static final int TIMEOUT_MS = 20_000;

    public enum Security { SSL, STARTTLS, NONE }

    public record Config(String host, int port, Security security, String user, String password, String fromAddress, String fromName) {}

    private final Config cfg;
    private Socket socket;
    private BufferedReader in;
    private OutputStream out;

    public EmailSender(Config cfg) {
        this.cfg = cfg;
    }

    public static boolean validAddress(String a) {
        return a != null && EMAIL.matcher(a).matches();
    }

    /** Gửi một email văn bản. Trả về Message-ID. Ném IOException kèm câu trả lời của máy chủ nếu lỗi. */
    public String send(String to, String subject, String text) throws IOException {
        if (cfg.host() == null || cfg.host().isBlank()) throw new IOException("Chưa cấu hình máy chủ SMTP");
        String from = cfg.fromAddress() == null || cfg.fromAddress().isBlank() ? cfg.user() : cfg.fromAddress();
        if (from == null || from.isBlank()) throw new IOException("Chưa nhập \"Tài khoản (email gửi)\" – nhập địa chỉ Gmail rồi bấm Lưu cấu hình");
        if (!validAddress(from.trim())) throw new IOException("Tài khoản (email gửi) không phải địa chỉ email hợp lệ: " + from);
        if (!validAddress(to)) throw new IOException("Địa chỉ nhận không hợp lệ: " + to);
        String domain = from.substring(from.indexOf('@') + 1);
        String messageId = "<" + UUID.randomUUID() + "@" + domain + ">";
        try {
            connect();
            expect(220);
            ehlo(domain);
            if (cfg.security() == Security.STARTTLS) {
                cmd("STARTTLS", 220);
                SSLSocket ssl = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault())
                        .createSocket(socket, cfg.host(), cfg.port(), true);
                ssl.startHandshake();
                bind(ssl);
                ehlo(domain);
            }
            if (cfg.user() != null && !cfg.user().isBlank()) {
                cmd("AUTH LOGIN", 334);
                cmd(b64(cfg.user()), 334);
                cmd(b64(cfg.password() == null ? "" : cfg.password()), 235);
            }
            cmd("MAIL FROM:<" + from + ">", 250);
            cmd("RCPT TO:<" + to + ">", 250, 251);
            cmd("DATA", 354);
            StringBuilder msg = new StringBuilder();
            String fromHeader = cfg.fromName() == null || cfg.fromName().isBlank() ? from : encodeWord(cfg.fromName()) + " <" + from + ">";
            msg.append("From: ").append(fromHeader).append("\r\n")
               .append("To: <").append(to).append(">\r\n")
               .append("Subject: ").append(encodeWord(subject)).append("\r\n")
               .append("Date: ").append(DateTimeFormatter.RFC_1123_DATE_TIME.withLocale(Locale.US).format(ZonedDateTime.now())).append("\r\n")
               .append("Message-ID: ").append(messageId).append("\r\n")
               .append("MIME-Version: 1.0\r\n")
               .append("Content-Type: text/plain; charset=UTF-8\r\n")
               .append("Content-Transfer-Encoding: base64\r\n\r\n");
            String body = Base64.getMimeEncoder(76, "\r\n".getBytes(StandardCharsets.US_ASCII))
                    .encodeToString(text.replace("\r\n", "\n").replace("\n", "\r\n").getBytes(StandardCharsets.UTF_8));
            msg.append(body).append("\r\n.\r\n");
            write(msg.toString());
            expect(250);
            try { cmd("QUIT", 221); } catch (IOException ignored) { /* đã gửi xong */ }
            return messageId;
        } finally {
            if (socket != null) try { socket.close(); } catch (IOException ignored) { }
        }
    }

    private void connect() throws IOException {
        Socket s;
        if (cfg.security() == Security.SSL) {
            s = SSLSocketFactory.getDefault().createSocket();
        } else {
            s = new Socket();
        }
        try {
            s.connect(new InetSocketAddress(cfg.host(), cfg.port()), TIMEOUT_MS);
        } catch (IOException e) {
            throw new IOException("Không kết nối được máy chủ SMTP " + cfg.host() + ":" + cfg.port()
                    + " – kiểm tra tên máy chủ, cổng và mạng Internet (" + e.getMessage() + ")", e);
        }
        s.setSoTimeout(TIMEOUT_MS);
        if (s instanceof SSLSocket ssl) ssl.startHandshake();
        bind(s);
    }

    private void bind(Socket s) throws IOException {
        socket = s;
        socket.setSoTimeout(TIMEOUT_MS);
        in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
        out = s.getOutputStream();
    }

    private void ehlo(String domain) throws IOException {
        cmd("EHLO " + domain.replaceAll("[^A-Za-z0-9.-]", ""), 250);
    }

    private void cmd(String line, int... ok) throws IOException {
        write(line + "\r\n");
        expect(ok);
    }

    private void write(String s) throws IOException {
        out.write(s.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    /** Đọc phản hồi (có thể nhiều dòng "250-...") và kiểm tra mã. */
    private void expect(int... ok) throws IOException {
        String line;
        StringBuilder all = new StringBuilder();
        do {
            line = in.readLine();
            if (line == null) throw new IOException("Máy chủ SMTP đóng kết nối");
            all.append(line).append(' ');
        } while (line.length() > 3 && line.charAt(3) == '-');
        int code;
        try { code = Integer.parseInt(line.substring(0, 3)); } catch (Exception e) { throw new IOException("Phản hồi SMTP lạ: " + line); }
        for (int c : ok) if (c == code) return;
        String msg = all.toString().trim();
        if (code == 535 || code == 534) msg += " (sai tài khoản/mật khẩu SMTP – với Gmail cần dùng Mật khẩu ứng dụng)";
        throw new IOException("SMTP: " + msg);
    }

    private static String b64(String s) {
        return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    /** Mã hóa tiêu đề có dấu theo RFC 2047. */
    static String encodeWord(String s) {
        String clean = s == null ? "" : s.replaceAll("[\\r\\n]+", " ");
        return "=?UTF-8?B?" + b64(clean) + "?=";
    }
}

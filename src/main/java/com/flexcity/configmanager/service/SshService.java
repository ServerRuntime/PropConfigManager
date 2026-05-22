package com.flexcity.configmanager.service;

import com.flexcity.configmanager.model.PropertyEntry;
import com.jcraft.jsch.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * SSH üzerinden uzak makine işlemleri.
 *
 * sudoUser desteği:
 *   machines.json'da "sudoUser": "flexcity" varsa dosya okuma/yazma
 *   sudo -S -u flexcity ile yapılır. SSH şifresi aynı zamanda sudo
 *   şifresi olarak kullanılır (sudo su flexcity ile aynı mantık).
 */
@Service
public class SshService {

    private static final Logger log = LoggerFactory.getLogger(SshService.class);

    @Value("${app.ssh.timeout:10000}")
    private int timeout;

    @Value("${app.remote.properties-path:/home/flexcity/java/flexcityconfig/config/default/application-env-shared.properties}")
    private String remotePath;

    // ─── Bağlantı ──────────────────────────────────────────────────────────────

    public Session openSession(String host, int port, String username, String password) throws JSchException {
        JSch jsch = new JSch();
        Session session = jsch.getSession(username, host, port);
        session.setPassword(password);
        Properties cfg = new Properties();
        cfg.put("StrictHostKeyChecking", "no");
        session.setConfig(cfg);
        session.connect(timeout);
        return session;
    }

    public void testConnection(String host, int port, String username, String password) throws JSchException {
        Session s = openSession(host, port, username, password);
        s.disconnect();
    }

    // ─── Genel exec ────────────────────────────────────────────────────────────

    /**
     * Güvenli okuma döngüsü — blocking read() kullanmaz.
     * channel.isClosed() true olduğunda available() ile kalan baytları drainler,
     * sonra çıkar. Hızlı biten komutlarda (is-active gibi) askıda kalmaz.
     */
    private String readChannel(ChannelExec channel, InputStream stdout) throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] tmp = new byte[4096];
        int n;
        while (true) {
            while (stdout.available() > 0) {
                n = stdout.read(tmp);
                if (n > 0) buf.write(tmp, 0, n);
            }
            if (channel.isClosed()) {
                // kanal kapandıktan sonra gelen son baytları da al
                while (stdout.available() > 0) {
                    n = stdout.read(tmp);
                    if (n > 0) buf.write(tmp, 0, n);
                }
                break;
            }
            Thread.sleep(50);
        }
        channel.disconnect();
        return buf.toString(StandardCharsets.UTF_8);
    }

    public String execCommand(Session session, String command) throws Exception {
        ChannelExec channel = (ChannelExec) session.openChannel("exec");
        channel.setCommand(command);

        InputStream stdout = channel.getInputStream();
        InputStream stderr = channel.getErrStream();
        channel.connect(timeout);

        // stderr'i ayrı oku (available tabanlı, bloklama yok)
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        byte[] tmp = new byte[4096];
        int n;

        // stdout'u readChannel ile oku, stderr'i paralel tüket
        ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
        while (true) {
            while (stdout.available() > 0) {
                n = stdout.read(tmp);
                if (n > 0) outBuf.write(tmp, 0, n);
            }
            while (stderr.available() > 0) {
                n = stderr.read(tmp);
                if (n > 0) errBuf.write(tmp, 0, n);
            }
            if (channel.isClosed()) {
                while (stdout.available() > 0) { n = stdout.read(tmp); if (n > 0) outBuf.write(tmp, 0, n); }
                while (stderr.available() > 0) { n = stderr.read(tmp); if (n > 0) errBuf.write(tmp, 0, n); }
                break;
            }
            Thread.sleep(50);
        }
        channel.disconnect();

        String errStr = errBuf.toString(StandardCharsets.UTF_8).trim();
        if (!errStr.isBlank() && !errStr.contains("[sudo]") && !errStr.contains("password for")) {
            log.debug("SSH stderr [{}]: {}", command, errStr);
        }
        return outBuf.toString(StandardCharsets.UTF_8);
    }

    /**
     * sudo -S -u {sudoUser} bash -c {cmd} ile çalıştırır.
     * SSH şifresi stdin üzerinden sudo'ya iletilir.
     */
    private String execWithSudo(Session session, String command,
                                String sudoUser, String password) throws Exception {
        ChannelExec channel = (ChannelExec) session.openChannel("exec");
        channel.setCommand("sudo -S -u " + sudoUser + " bash -c " + shellQuote(command) + " 2>&1");

        OutputStream stdin  = channel.getOutputStream();
        InputStream  stdout = channel.getInputStream();
        channel.connect(timeout);

        stdin.write((password + "\n").getBytes(StandardCharsets.UTF_8));
        stdin.flush();

        String raw = readChannel(channel, stdout);

        // sudo prompt'u satır başından sıyır (prompt ve çıktı aynı satırda gelebilir)
        // Örn: "[sudo] password for ertan.eryilmaz: active" → "active"
        return raw.replaceAll("(?m)\\[sudo\\] password for [^\\n:]+:\\s*", "").trim();
    }

    // ─── Dosya okuma ───────────────────────────────────────────────────────────

    private String readRemoteFile(Session session, String sudoUser, String password) throws Exception {
        if (sudoUser != null && !sudoUser.isBlank()) {
            // sudo -S -u flexcity cat /path/to/file
            ChannelExec channel = (ChannelExec) session.openChannel("exec");
            channel.setCommand("sudo -S -u " + sudoUser + " cat \"" + remotePath + "\" 2>/dev/null");

            OutputStream stdin  = channel.getOutputStream();
            InputStream  stdout = channel.getInputStream();
            channel.connect(timeout);

            stdin.write((password + "\n").getBytes(StandardCharsets.UTF_8));
            stdin.flush();

            return readChannel(channel, stdout);
        } else {
            return execCommand(session, "cat \"" + remotePath + "\"");
        }
    }

    // ─── Dosya yazma ───────────────────────────────────────────────────────────

    /**
     * sudoUser varsa:
     *   1. SFTP ile /tmp'ye geçici dosya yazar (SSH kullanıcısı yetkileriyle)
     *   2. sudo -u flexcity ile asıl konuma kopyalar, sahipliği düzeltir
     *   3. /tmp'deki geçici dosyayı temizler
     *
     * sudoUser yoksa: doğrudan SFTP ile yazar (backup + overwrite).
     */
    private void writeRemoteFile(Session session, String content,
                                 String sudoUser, String password) throws Exception {
        if (sudoUser != null && !sudoUser.isBlank()) {
            String tmpFile = "/tmp/fcm_" + System.currentTimeMillis() + ".properties";

            // 1) /tmp'ye geçici dosya yaz
            writeSftp(session, content, tmpFile);

            // 2) Yedek al
            execWithSudo(session,
                    "cp \"" + remotePath + "\" \"" + remotePath + ".bak\" 2>/dev/null || true",
                    sudoUser, password);

            // 3) Hedef konuma taşı + sahipliği düzelt
            execWithSudo(session,
                    "cp \"" + tmpFile + "\" \"" + remotePath + "\" && "
                    + "chown " + sudoUser + ":" + sudoUser + " \"" + remotePath + "\"",
                    sudoUser, password);

            // 4) Geçici dosyayı sil
            execCommand(session, "rm -f \"" + tmpFile + "\"");

            log.info("Dosya yazıldı (sudo -u {}): {}", sudoUser, remotePath);
        } else {
            ChannelSftp sftp = (ChannelSftp) session.openChannel("sftp");
            sftp.connect(timeout);
            try {
                try { sftp.rename(remotePath, remotePath + ".bak"); } catch (Exception ignored) {}
            } finally {
                sftp.disconnect();
            }
            writeSftp(session, content, remotePath);
        }
    }

    private void writeSftp(Session session, String content, String path) throws Exception {
        ChannelSftp sftp = (ChannelSftp) session.openChannel("sftp");
        sftp.connect(timeout);
        try (InputStream is = new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8))) {
            sftp.put(is, path);
        } finally {
            sftp.disconnect();
        }
    }

    // ─── Properties CRUD ───────────────────────────────────────────────────────

    public List<PropertyEntry> readProperties(String host, int port,
                                              String username, String password,
                                              String sudoUser) throws Exception {
        Session session = openSession(host, port, username, password);
        try {
            String content = readRemoteFile(session, sudoUser, password);
            return parseProperties(content).stream()
                    .filter(e -> e.getType() == PropertyEntry.Type.PROPERTY)
                    .toList();
        } finally {
            session.disconnect();
        }
    }

    public void addProperty(String host, int port, String username, String password,
                            String sudoUser, String key, String value) throws Exception {
        Session session = openSession(host, port, username, password);
        try {
            List<PropertyEntry> entries = parseProperties(readRemoteFile(session, sudoUser, password));
            boolean exists = entries.stream()
                    .anyMatch(e -> e.getType() == PropertyEntry.Type.PROPERTY && key.equals(e.getKey()));
            if (exists) throw new IllegalArgumentException("'" + key + "' zaten mevcut");
            entries.add(PropertyEntry.property(key, value));
            writeRemoteFile(session, buildContent(entries), sudoUser, password);
        } finally {
            session.disconnect();
        }
    }

    public void updateProperty(String host, int port, String username, String password,
                               String sudoUser, String key, String newValue) throws Exception {
        Session session = openSession(host, port, username, password);
        try {
            List<PropertyEntry> entries = parseProperties(readRemoteFile(session, sudoUser, password));
            boolean found = false;
            for (PropertyEntry e : entries) {
                if (e.getType() == PropertyEntry.Type.PROPERTY && key.equals(e.getKey())) {
                    e.setValue(newValue);
                    found = true;
                    break;
                }
            }
            if (!found) throw new IllegalArgumentException("'" + key + "' bulunamadı");
            writeRemoteFile(session, buildContent(entries), sudoUser, password);
        } finally {
            session.disconnect();
        }
    }

    public void deleteProperty(String host, int port, String username, String password,
                               String sudoUser, String key) throws Exception {
        Session session = openSession(host, port, username, password);
        try {
            List<PropertyEntry> entries = parseProperties(readRemoteFile(session, sudoUser, password));
            long before = entries.size();
            entries.removeIf(e -> e.getType() == PropertyEntry.Type.PROPERTY && key.equals(e.getKey()));
            if (entries.size() == before) throw new IllegalArgumentException("'" + key + "' bulunamadı");
            writeRemoteFile(session, buildContent(entries), sudoUser, password);
        } finally {
            session.disconnect();
        }
    }

    // ─── Servis kontrolü ───────────────────────────────────────────────────────

    /**
     * sudo -S <command> — şifre stdin'den verilir, kullanıcı değiştirilmez (root yetkisi).
     * systemctl start/stop/restart/is-active için kullanılır.
     */
    private String execSudoRoot(Session session, String command, String password) throws Exception {
        ChannelExec channel = (ChannelExec) session.openChannel("exec");
        channel.setCommand("sudo -S " + command + " 2>&1");

        OutputStream stdin  = channel.getOutputStream();
        InputStream  stdout = channel.getInputStream();
        channel.connect(timeout);

        stdin.write((password + "\n").getBytes(StandardCharsets.UTF_8));
        stdin.flush();

        String raw = readChannel(channel, stdout);

        // sudo prompt'u satır başından sıyır (prompt ve çıktı aynı satırda gelebilir)
        // Örn: "[sudo] password for ertan.eryilmaz: active" → "active"
        return raw.replaceAll("(?m)\\[sudo\\] password for [^\\n:]+:\\s*", "").trim();
    }

    public String getServiceStatus(String host, int port, String username, String password,
                                   String serviceName) throws Exception {
        Session session = openSession(host, port, username, password);
        try {
            // sudo -S systemctl is-active <service> — şifre stdin'den
            String out = execSudoRoot(session,
                    "systemctl is-active " + serviceName, password).trim();
            return switch (out) {
                case "active"   -> "active";
                case "inactive" -> "inactive";
                case "failed"   -> "failed";
                default         -> "unknown";
            };
        } finally {
            session.disconnect();
        }
    }

    public void startService(String host, int port, String username, String password,
                             String serviceName) throws Exception {
        runServiceCmd(host, port, username, password, "start", serviceName);
    }

    public void stopService(String host, int port, String username, String password,
                            String serviceName) throws Exception {
        runServiceCmd(host, port, username, password, "stop", serviceName);
    }

    public void restartService(String host, int port, String username, String password,
                               String serviceName) throws Exception {
        runServiceCmd(host, port, username, password, "restart", serviceName);
    }

    private void runServiceCmd(String host, int port, String username, String password,
                               String action, String serviceName) throws Exception {
        Session session = openSession(host, port, username, password);
        try {
            String out = execSudoRoot(session,
                    "systemctl " + action + " " + serviceName, password).trim();
            log.info("Service {} {} @ {}:{} -> {}", action, serviceName, host, port,
                    out.isBlank() ? "OK" : out);
        } finally {
            session.disconnect();
        }
    }

    // ─── Properties parse / build ─────────────────────────────────────────────

    public List<PropertyEntry> parseProperties(String content) {
        List<PropertyEntry> result = new ArrayList<>();
        for (String line : content.split("\n", -1)) {
            String trimmed = line.strip();
            if (trimmed.isEmpty()) {
                result.add(PropertyEntry.blank());
            } else if (trimmed.startsWith("#") || trimmed.startsWith("!")) {
                result.add(PropertyEntry.comment(line));
            } else {
                int sepIdx = findSeparator(trimmed);
                if (sepIdx > 0) {
                    result.add(PropertyEntry.property(
                            trimmed.substring(0, sepIdx).strip(),
                            trimmed.substring(sepIdx + 1).strip()));
                } else {
                    result.add(PropertyEntry.comment(line));
                }
            }
        }
        return result;
    }

    private int findSeparator(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c == '=' || c == ':') && (i == 0 || s.charAt(i - 1) != '\\')) return i;
        }
        return -1;
    }

    public String buildContent(List<PropertyEntry> entries) {
        StringBuilder sb = new StringBuilder();
        for (PropertyEntry e : entries) {
            switch (e.getType()) {
                case BLANK    -> sb.append('\n');
                case COMMENT  -> sb.append(e.getRawLine()).append('\n');
                case PROPERTY -> sb.append(e.getKey()).append('=').append(e.getValue()).append('\n');
            }
        }
        return sb.toString();
    }

    private String shellQuote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    // ─── Canlı log izleme ─────────────────────────────────────────────────────

    /**
     * SSH üzerinden tail -f ile log dosyasını canlı izler.
     * sudoUser varsa sudo -S -u {sudoUser} tail -f ... olarak çalışır.
     *
     * @param lineCallback  her satır için çağrılır
     * @param stopped       true döndüğünde döngü kesilir (SSE bağlantısı koptuğunda)
     */
    public void tailLog(String host, int port, String username, String password,
                        String sudoUser, String logFile,
                        Consumer<String> lineCallback,
                        AtomicBoolean stopped) throws Exception {
        Session session = openSession(host, port, username, password);
        try {
            ChannelExec channel = (ChannelExec) session.openChannel("exec");

            String cmd = (sudoUser != null && !sudoUser.isBlank())
                    ? "sudo -S -u " + sudoUser + " tail -f \"" + logFile + "\" 2>&1"
                    : "tail -f \"" + logFile + "\" 2>&1";
            channel.setCommand(cmd);

            OutputStream stdin  = channel.getOutputStream();
            InputStream  stdout = channel.getInputStream();
            channel.connect(timeout);

            if (sudoUser != null && !sudoUser.isBlank()) {
                stdin.write((password + "\n").getBytes(StandardCharsets.UTF_8));
                stdin.flush();
            }

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(stdout, StandardCharsets.UTF_8));
            char[] buf = new char[4096];
            StringBuilder lineBuf = new StringBuilder();

            while (!stopped.get() && !channel.isClosed()) {
                if (stdout.available() > 0) {
                    int n = reader.read(buf);
                    if (n > 0) {
                        lineBuf.append(buf, 0, n);
                        // Tam satırları gönder, yarım kalan sonraki okumaya kalsın
                        int lastNl = lineBuf.lastIndexOf("\n");
                        if (lastNl >= 0) {
                            String[] lines = lineBuf.substring(0, lastNl).split("\n", -1);
                            for (String line : lines) {
                                // sudo prompt satırını atla
                                if (!line.contains("[sudo]") && !line.contains("password for")) {
                                    lineCallback.accept(line);
                                }
                            }
                            lineBuf = new StringBuilder(lineBuf.substring(lastNl + 1));
                        }
                    }
                } else {
                    Thread.sleep(100);
                }
            }

            channel.disconnect();
        } finally {
            session.disconnect();
        }
    }
}

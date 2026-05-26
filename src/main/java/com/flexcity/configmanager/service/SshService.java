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

    // ─── Temel exec ────────────────────────────────────────────────────────────

    /**
     * Sudo olmayan genel komut çalıştırıcı.
     */
    public String execCommand(Session session, String command) throws Exception {
        log.debug("[SSH] execCommand: {}", command);
        ChannelExec channel = (ChannelExec) session.openChannel("exec");
        channel.setCommand(command);

        InputStream stdout = channel.getInputStream();
        InputStream stderr = channel.getErrStream();
        channel.connect(timeout);

        ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        byte[] tmp = new byte[4096];
        int n;

        while (true) {
            while (stdout.available() > 0) { n = stdout.read(tmp); if (n > 0) outBuf.write(tmp, 0, n); }
            while (stderr.available() > 0) { n = stderr.read(tmp); if (n > 0) errBuf.write(tmp, 0, n); }
            if (channel.isClosed()) {
                while (stdout.available() > 0) { n = stdout.read(tmp); if (n > 0) outBuf.write(tmp, 0, n); }
                while (stderr.available() > 0) { n = stderr.read(tmp); if (n > 0) errBuf.write(tmp, 0, n); }
                break;
            }
            Thread.sleep(50);
        }
        channel.disconnect();

        String err = errBuf.toString(StandardCharsets.UTF_8).trim();
        if (!err.isBlank()) log.warn("[SSH] stderr for '{}': {}", command, err);
        return outBuf.toString(StandardCharsets.UTF_8);
    }

    /**
     * Sudo komutu çalıştırır.
     *
     * - stdout + stderr birleştirilir (2>&1) — hata mesajları kaybedilmez.
     * - Şifre stdout'ta gözükebilecek sudo prompt'u regex ile temizlenir.
     * - Dönen ham çıktı DEBUG seviyesinde loglanır; sorun tespitinde kullanılır.
     */
    private String sudoExec(Session session, String command, String password) throws Exception {
        String fullCmd = command + " 2>&1";
        log.debug("[SSH] sudoExec: {}", fullCmd);

        ChannelExec channel = (ChannelExec) session.openChannel("exec");
        channel.setCommand(fullCmd);

        OutputStream stdin  = channel.getOutputStream();
        InputStream  stdout = channel.getInputStream();
        channel.connect(timeout);

        // Şifreyi connect() hemen ardından gönder.
        // sudo -S stdin'i bekliyor; kısa bekleme prompt'un gelmesine olanak tanır.
        Thread.sleep(150);
        stdin.write((password + "\n").getBytes(StandardCharsets.UTF_8));
        stdin.flush();

        String raw = readChannel(channel, stdout);

        // Ham çıktıyı logla — neyin döndüğünü görmek sorun tespitinde kritik
        if (raw.isBlank()) {
            log.warn("[SSH] sudoExec boş çıktı döndü. Komut: {}", fullCmd);
        } else {
            log.debug("[SSH] sudoExec ham çıktı ({} karakter): {}", raw.length(),
                    raw.length() > 300 ? raw.substring(0, 300) + "..." : raw);
        }

        String result = stripSudoPrompt(raw);

        // Açık hata mesajlarını WARN olarak logla
        if (result.contains("sudo: sorry") || result.contains("no tty present")
                || result.contains("not allowed to execute") || result.contains("Permission denied")
                || result.contains("No such file") || result.contains("cannot open")) {
            log.warn("[SSH] sudoExec hata içeriyor. Komut: {}\nÇıktı: {}", fullCmd, result);
        }

        return result;
    }

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

    private String stripSudoPrompt(String raw) {
        return raw
            .replaceAll("(?m)^\\[sudo\\] password for [^\\n:]+:\\s*\\r?\\n?", "")
            .replaceAll("\\[sudo\\] password for [^\\n:]+:\\s*", "");
    }

    /**
     * sudo çıktısının "bu kullanıcı yetkisiz" hatası içerip içermediğini kontrol eder.
     * Bu durumda root sudo (sudo olmadan -u) ile fallback yapılabilir.
     */
    private boolean isSudoPermissionError(String output) {
        return output.contains("not allowed to execute")
                || output.contains("sudo: sorry")
                || output.contains("is not in the sudoers");
    }

    // ─── Dosya okuma ───────────────────────────────────────────────────────────

    private String readRemoteFile(Session session, String sudoUser, String password) throws Exception {
        if (sudoUser != null && !sudoUser.isBlank()) {
            log.debug("[SSH] readRemoteFile (sudo -u {}): {}", sudoUser, remotePath);
            String result = sudoExec(session,
                    "sudo -S -u " + sudoUser + " cat \"" + remotePath + "\"",
                    password);
            // Bazı makinelerde 'sudo -u <user>' izni yoktur; bu durumda root sudo ile tekrar dene
            if (isSudoPermissionError(result)) {
                log.warn("[SSH] 'sudo -u {}' izinli değil; root sudo ile tekrar deneniyor: {}", sudoUser, remotePath);
                result = sudoExec(session, "sudo -S cat \"" + remotePath + "\"", password);
            }
            return result;
        } else {
            log.debug("[SSH] readRemoteFile (direct): {}", remotePath);
            return execCommand(session, "cat \"" + remotePath + "\"");
        }
    }

    // ─── Dosya yazma ───────────────────────────────────────────────────────────

    private void writeRemoteFile(Session session, String content,
                                 String sudoUser, String password) throws Exception {
        if (sudoUser != null && !sudoUser.isBlank()) {
            String tmpFile = "/tmp/fcm_" + System.currentTimeMillis() + ".properties";
            writeSftp(session, content, tmpFile);

            // Backup dene; hata "not allowed" ise root sudo'ya geç
            String backupResult = sudoExec(session,
                    "sudo -S -u " + sudoUser + " bash -c "
                    + shellQuote("cp \"" + remotePath + "\" \"" + remotePath + ".bak\" 2>/dev/null || true"),
                    password);

            if (isSudoPermissionError(backupResult)) {
                log.warn("[SSH] 'sudo -u {}' izinli değil; dosya root sudo ile yazılıyor: {}", sudoUser, remotePath);
                sudoExec(session,
                        "sudo -S bash -c "
                        + shellQuote("cp \"" + remotePath + "\" \"" + remotePath + ".bak\" 2>/dev/null || true"),
                        password);
                sudoExec(session,
                        "sudo -S bash -c "
                        + shellQuote("cp \"" + tmpFile + "\" \"" + remotePath + "\" && "
                                   + "chown " + sudoUser + ":" + sudoUser + " \"" + remotePath + "\""),
                        password);
            } else {
                sudoExec(session,
                        "sudo -S -u " + sudoUser + " bash -c "
                        + shellQuote("cp \"" + tmpFile + "\" \"" + remotePath + "\" && "
                                   + "chown " + sudoUser + ":" + sudoUser + " \"" + remotePath + "\""),
                        password);
            }

            execCommand(session, "rm -f \"" + tmpFile + "\"");
            log.info("[SSH] Dosya yazıldı: {}", remotePath);
        } else {
            ChannelSftp sftp = (ChannelSftp) session.openChannel("sftp");
            sftp.connect(timeout);
            try { sftp.rename(remotePath, remotePath + ".bak"); } catch (Exception ignored) {}
            sftp.disconnect();
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
            if (content.isBlank()) {
                log.warn("[SSH] readProperties: {} adresinden boş içerik döndü ({}:{})", remotePath, host, port);
            }
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

    public String getServiceStatus(String host, int port, String username, String password,
                                   String serviceName) throws Exception {
        Session session = openSession(host, port, username, password);
        try {
            String out = sudoExec(session,
                    "sudo -S systemctl is-active " + serviceName, password).trim();
            log.debug("[SSH] serviceStatus {} @ {}: '{}'", serviceName, host, out);
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
            String out = sudoExec(session,
                    "sudo -S systemctl " + action + " " + serviceName, password).trim();
            log.info("[SSH] service {} {} @ {} -> {}", action, serviceName, host,
                    out.isBlank() ? "OK" : out);
        } finally {
            session.disconnect();
        }
    }

    // ─── Canlı log izleme ─────────────────────────────────────────────────────

    public void tailLog(String host, int port, String username, String password,
                        String sudoUser, String logFile,
                        Consumer<String> lineCallback,
                        AtomicBoolean stopped) throws Exception {
        Session session = openSession(host, port, username, password);
        try {
            // sudo -u <user> iznini önceden test et; başarısızsa root sudo kullan
            String tailCmd;
            boolean sendPassword = false;
            if (sudoUser != null && !sudoUser.isBlank()) {
                String test = sudoExec(session, "sudo -S -u " + sudoUser + " echo ok", password);
                if (isSudoPermissionError(test)) {
                    log.warn("[SSH] tailLog: 'sudo -u {}' izinli değil, root sudo kullanılıyor", sudoUser);
                    tailCmd = "sudo -S tail -f \"" + logFile + "\" 2>&1";
                } else {
                    tailCmd = "sudo -S -u " + sudoUser + " tail -f \"" + logFile + "\" 2>&1";
                }
                sendPassword = true;
            } else {
                tailCmd = "tail -f \"" + logFile + "\" 2>&1";
            }

            log.debug("[SSH] tailLog: {}", tailCmd);
            ChannelExec channel = (ChannelExec) session.openChannel("exec");
            channel.setCommand(tailCmd);

            OutputStream stdin  = channel.getOutputStream();
            InputStream  stdout = channel.getInputStream();
            channel.connect(timeout);

            if (sendPassword) {
                Thread.sleep(150);
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
                        int lastNl = lineBuf.lastIndexOf("\n");
                        if (lastNl >= 0) {
                            String[] lines = lineBuf.substring(0, lastNl).split("\n", -1);
                            for (String line : lines) {
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
}

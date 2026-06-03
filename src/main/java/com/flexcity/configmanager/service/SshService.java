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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
                        int historyLines,
                        Consumer<String> lineCallback,
                        AtomicBoolean stopped) throws Exception {
        Session session = openSession(host, port, username, password);
        // historyLines=0 → -n 0 (hiç geçmiş yok, sadece yeni satırlar), >0 → son N satır
        String nArg = "-n " + historyLines + " ";
        try {
            // sudo -u <user> iznini önceden test et; başarısızsa root sudo kullan
            String tailCmd;
            boolean sendPassword = false;
            if (sudoUser != null && !sudoUser.isBlank()) {
                String test = sudoExec(session, "sudo -S -u " + sudoUser + " echo ok", password);
                if (isSudoPermissionError(test)) {
                    log.warn("[SSH] tailLog: 'sudo -u {}' izinli değil, root sudo kullanılıyor", sudoUser);
                    tailCmd = "sudo -S tail " + nArg + "-f \"" + logFile + "\" 2>&1";
                } else {
                    tailCmd = "sudo -S -u " + sudoUser + " tail " + nArg + "-f \"" + logFile + "\" 2>&1";
                }
                sendPassword = true;
            } else {
                tailCmd = "tail " + nArg + "-f \"" + logFile + "\" 2>&1";
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

    // ─── Sistem bilgisi ───────────────────────────────────────────────────────

    /**
     * Uzak sunucudan CPU, RAM, disk, uptime ve OS bilgilerini tek SSH oturumunda çeker.
     * Her komut tek satır / parse edilebilir çıktı verecek şekilde seçildi.
     */
    public Map<String, Object> getSystemInfo(String host, int port,
                                              String username, String password,
                                              String serviceName) throws Exception {
        Session session = openSession(host, port, username, password);
        try {
            Map<String, Object> result = new LinkedHashMap<>();

            // ── Uptime ────────────────────────────────────────────────────────
            // Örnek çıktı: " 14 days, 6:23,  2 users,  load average: 1.21, 0.89, 0.75"
            String uptimeRaw = execCommand(session, "uptime").trim();
            result.put("uptime", uptimeRaw);

            // ── Load average (kısa) ───────────────────────────────────────────
            // Örnek: "1.21 0.89 0.75"
            String loadRaw = execCommand(session, "cat /proc/loadavg").trim();
            String[] loadParts = loadRaw.split("\\s+");
            result.put("load1",  loadParts.length > 0 ? loadParts[0] : "?");
            result.put("load5",  loadParts.length > 1 ? loadParts[1] : "?");
            result.put("load15", loadParts.length > 2 ? loadParts[2] : "?");

            // ── CPU çekirdek sayısı ────────────────────────────────────────────
            String cpuCores = execCommand(session, "nproc").trim();
            result.put("cpuCores", cpuCores);

            // ── CPU kullanım % (1 saniyelik örnek) ────────────────────────────
            // `top -bn1` çıktısından %Cpu satırı: "Cpu(s): 12.5 us, 3.2 sy, ..."
            String cpuLine = execCommand(session,
                    "top -bn1 | grep '%Cpu\\|Cpu(' | head -1").trim();
            result.put("cpuLine", cpuLine);

            // idle değerini parse et → kullanım = 100 - idle
            try {
                // "99.5 id" veya "99.5%id" formatında
                Matcher m = Pattern
                        .compile("([\\d.]+)\\s*[%]?\\s*id").matcher(cpuLine);
                if (m.find()) {
                    double idle = Double.parseDouble(m.group(1));
                    result.put("cpuUsagePct", Math.round(100.0 - idle));
                } else {
                    result.put("cpuUsagePct", -1);
                }
            } catch (Exception ignored) {
                result.put("cpuUsagePct", -1);
            }

            // ── RAM ───────────────────────────────────────────────────────────
            // free -m çıktısı:
            //               total  used  free  shared  buff/cache  available
            // Mem:           8192  5800   312     120        2079       2162
            String freeRaw = execCommand(session, "free -m | grep '^Mem:'").trim();
            String[] freeParts = freeRaw.split("\\s+");
            if (freeParts.length >= 3) {
                long total = parseLong(freeParts[1]);
                long used  = parseLong(freeParts[2]);
                long avail = freeParts.length >= 7 ? parseLong(freeParts[6]) : total - used;
                result.put("ramTotalMb", total);
                result.put("ramUsedMb",  used);
                result.put("ramAvailMb", avail);
                result.put("ramUsagePct", total > 0 ? (int) Math.round(used * 100.0 / total) : 0);
            }

            // ── Disk bölümleri ────────────────────────────────────────────────
            // df -h çıktısı: Filesystem  Size  Used  Avail  Use%  Mounted on
            String dfRaw = execCommand(session,
                    "df -h | grep -v '^Filesystem' | grep -v '^tmpfs\\|^devtmpfs\\|^udev\\|loop'").trim();
            // df -h çıktısı: Filesystem  Size  Used  Avail  Use%  Mounted on
            List<Map<String, String>> mounts = new ArrayList<>();
            for (String line : dfRaw.split("\n")) {
                String[] p = line.trim().split("\\s+");
                if (p.length >= 6) {
                    // Son kolon mount point, diğerleri sırayla
                    String mountPt = p[p.length - 1];
                    String pct     = p[p.length - 2].replace("%", "");
                    String avail   = p[p.length - 3];
                    String used    = p[p.length - 4];
                    String size    = p[p.length - 5];
                    Map<String, String> mount = new LinkedHashMap<>();
                    mount.put("mount", mountPt);
                    mount.put("size",  size);
                    mount.put("used",  used);
                    mount.put("avail", avail);
                    mount.put("pct",   pct);
                    mounts.add(mount);
                }
            }
            result.put("diskMounts", mounts);

            // Ana disk (/) kullanımı özet olarak da ver
            mounts.stream().filter(mt -> "/".equals(mt.get("mount"))).findFirst().ifPresent(root -> {
                try { result.put("diskUsagePct", Integer.parseInt(root.get("pct"))); } catch (Exception ignored) {}
                result.put("diskSize",  root.get("size"));
                result.put("diskUsed",  root.get("used"));
                result.put("diskAvail", root.get("avail"));
            });

            // ── Servis başlangıç zamanı ───────────────────────────────────────
            if (serviceName != null && !serviceName.isBlank()) {
                String svcTimestamp = sudoExec(session,
                        "sudo -S systemctl show " + serviceName
                        + " --property=ActiveEnterTimestamp --value 2>/dev/null",
                        password).trim();
                // Örnek: "Mon 2026-05-19 08:23:41 +03"
                svcTimestamp = stripSudoPrompt(svcTimestamp).trim();
                result.put("serviceStartTime", svcTimestamp.isBlank() ? "—" : svcTimestamp);

                String svcRestarts = sudoExec(session,
                        "sudo -S systemctl show " + serviceName
                        + " --property=NRestarts --value 2>/dev/null",
                        password).trim();
                svcRestarts = stripSudoPrompt(svcRestarts).trim();
                result.put("serviceRestarts", svcRestarts.isBlank() ? "0" : svcRestarts);
            } else {
                result.put("serviceStartTime", "—");
                result.put("serviceRestarts",  "—");
            }

            // ── OS ve hostname ────────────────────────────────────────────────
            result.put("hostname", execCommand(session, "hostname").trim());
            String osRelease = execCommand(session,
                    "cat /etc/os-release | grep '^PRETTY_NAME' | cut -d= -f2 | tr -d '\"'").trim();
            result.put("os", osRelease.isBlank() ? "Linux" : osRelease);

            // ── Uptime insan okunabilir ────────────────────────────────────────
            String uptimeHuman = execCommand(session, "uptime -p 2>/dev/null || echo ''").trim();
            result.put("uptimeHuman", translateUptime(uptimeHuman));

            // ── Java versiyonu ─────────────────────────────────────────────────
            String javaVer = execCommand(session, "java -version 2>&1 | head -1").trim();
            result.put("javaVersion", javaVer.isBlank() ? "—" : javaVer);

            return result;
        } finally {
            session.disconnect();
        }
    }

    private long parseLong(String s) {
        try { return Long.parseLong(s.trim()); } catch (Exception e) { return 0L; }
    }

    /**
     * "up 2 weeks, 3 days, 4 hours, 5 minutes" → "2 hafta, 3 gün, 4 saat, 5 dakika"
     */
    private String translateUptime(String raw) {
        if (raw == null || raw.isBlank()) return "—";
        String s = raw.replaceAll("^up\\s+", "").trim();
        s = s.replaceAll("(\\d+)\\s+years?",   "$1 yıl");
        s = s.replaceAll("(\\d+)\\s+weeks?",   "$1 hafta");
        s = s.replaceAll("(\\d+)\\s+days?",    "$1 gün");
        s = s.replaceAll("(\\d+)\\s+hours?",   "$1 saat");
        s = s.replaceAll("(\\d+)\\s+minutes?", "$1 dakika");
        s = s.replaceAll("(\\d+)\\s+seconds?", "$1 saniye");
        return s.isBlank() ? "—" : s;
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

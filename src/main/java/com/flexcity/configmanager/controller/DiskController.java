package com.flexcity.configmanager.controller;

import com.flexcity.configmanager.model.Machine;
import com.flexcity.configmanager.service.MachineService;
import com.flexcity.configmanager.service.SshService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/disk")
public class DiskController {

    private static final Logger log = LoggerFactory.getLogger(DiskController.class);

    private final MachineService machineService;
    private final SshService     sshService;

    public DiskController(MachineService machineService, SshService sshService) {
        this.machineService = machineService;
        this.sshService     = sshService;
    }

    // ─── Log dosyalarını listele ───────────────────────────────────────────────

    @GetMapping("/{machineId}/logs")
    public ResponseEntity<Map<String, Object>> listLogs(
            @PathVariable String machineId,
            @RequestParam(required = false) String username,
            @RequestParam(required = false) String password) {

        Optional<Machine> opt = machineService.findById(machineId);
        if (opt.isEmpty()) return notFound("Makine bulunamadı");
        Machine m = opt.get();

        // logFile tanımlı değilse genel log dizinlerini tara
        String logFile = (m.getLogFile() != null && !m.getLogFile().isBlank())
                ? m.getLogFile() : "/var/log/syslog";

        String[] creds = resolveCreds(m, username, password);
        if (creds == null) return badRequest("Kimlik bilgisi gerekli");

        try {
            List<Map<String, String>> files = sshService.listLogFiles(
                    m.getHost(), m.getPort(), creds[0], creds[1], logFile, m.getSudoUser());

            long totalBytes = files.stream()
                    .mapToLong(f -> { try { return Long.parseLong(f.get("size")); } catch (Exception e) { return 0L; } })
                    .sum();

            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("success",    true);
            resp.put("files",      files);
            resp.put("totalSize",  totalBytes);
            resp.put("totalHuman", humanSize(totalBytes));
            resp.put("activeLog",  logFile);
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            log.warn("[Disk] listLogs hatası [{}]: {}", m.getName(), e.getMessage());
            return serverError(e.getMessage());
        }
    }

    // ─── Log dosyası sil ──────────────────────────────────────────────────────

    @DeleteMapping("/{machineId}/log")
    public ResponseEntity<Map<String, Object>> deleteLog(
            @PathVariable String machineId,
            @RequestBody Map<String, String> body) {

        Optional<Machine> opt = machineService.findById(machineId);
        if (opt.isEmpty()) return notFound("Makine bulunamadı");
        Machine m = opt.get();

        String filePath = body.get("path");
        if (filePath == null || filePath.isBlank()) return badRequest("Dosya yolu gerekli");

        String[] creds = resolveCreds(m, body.get("username"), body.get("password"));
        if (creds == null) return badRequest("Kimlik bilgisi gerekli");

        try {
            sshService.deleteLogFile(
                    m.getHost(), m.getPort(), creds[0], creds[1],
                    filePath, m.getLogFile(), m.getSudoUser());

            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("success", true);
            resp.put("message", "Silindi: " + filePath);
            return ResponseEntity.ok(resp);
        } catch (SecurityException e) {
            return badRequest(e.getMessage());
        } catch (Exception e) {
            log.warn("[Disk] deleteLog hatası [{}]: {}", m.getName(), e.getMessage());
            return serverError(e.getMessage());
        }
    }

    // ─── Yardımcılar ──────────────────────────────────────────────────────────

    private String[] resolveCreds(Machine m, String reqUser, String reqPass) {
        if (m.isHasCredentials()) return new String[]{m.getUsername(), m.getPassword()};
        if (reqUser != null && !reqUser.isBlank() && reqPass != null && !reqPass.isBlank())
            return new String[]{reqUser, reqPass};
        return null;
    }

    private String humanSize(long bytes) {
        if (bytes < 1024)       return bytes + " B";
        if (bytes < 1048576)    return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1073741824) return String.format("%.1f MB", bytes / 1048576.0);
        return String.format("%.2f GB", bytes / 1073741824.0);
    }

    private ResponseEntity<Map<String, Object>> notFound(String msg) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("success", false, "error", msg));
    }
    private ResponseEntity<Map<String, Object>> badRequest(String msg) {
        return ResponseEntity.badRequest().body(Map.of("success", false, "error", msg));
    }
    private ResponseEntity<Map<String, Object>> serverError(String msg) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("success", false, "error", msg));
    }
}

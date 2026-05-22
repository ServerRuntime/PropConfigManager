package com.flexcity.configmanager.controller;

import com.flexcity.configmanager.model.Machine;
import com.flexcity.configmanager.model.PropertyEntry;
import com.flexcity.configmanager.service.MachineService;
import com.flexcity.configmanager.service.SshService;
import com.jcraft.jsch.JSchException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * REST API — tüm istemci/sunucu iletişimi.
 *
 * Kimlik çözümleme:
 *   machines.json'da username+password varsa → JSON kullanılır (body'e gerek yok)
 *   Yoksa → request body'deki username/password kullanılır
 */
@RestController
@RequestMapping("/api")
public class ApiController {

    private static final Logger log = LoggerFactory.getLogger(ApiController.class);

    private final MachineService machineService;
    private final SshService     sshService;

    public ApiController(MachineService machineService, SshService sshService) {
        this.machineService = machineService;
        this.sshService     = sshService;
    }

    // ─── Makine listesi ────────────────────────────────────────────────────────

    @GetMapping("/machines")
    public Map<String, Object> getMachines() {
        return Map.of("success", true, "machines", machineService.getAll());
    }

    // ─── Bağlantı testi ────────────────────────────────────────────────────────

    @PostMapping("/test-connection")
    public ResponseEntity<Map<String, Object>> testConnection(@RequestBody Map<String, String> body) {
        String machineId = body.get("machineId");
        Optional<Machine> opt = machineService.findById(machineId);
        if (opt.isEmpty()) return notFound("Makine bulunamadı: " + machineId);

        Machine m      = opt.get();
        String[] creds = resolveCreds(m, body.get("username"), body.get("password"));
        if (creds == null) return badRequest("Kimlik bilgisi gerekli");

        try {
            sshService.testConnection(m.getHost(), m.getPort(), creds[0], creds[1]);
            return ok(Map.of("message", m.getName() + " bağlantısı başarılı"));
        } catch (JSchException e) {
            return authOrSsh(e);
        } catch (Exception e) {
            return serverError("Bağlantı hatası: " + e.getMessage());
        }
    }

    // ─── Properties okuma ──────────────────────────────────────────────────────

    @GetMapping("/properties/{machineId}")
    public ResponseEntity<Map<String, Object>> getProperties(
            @PathVariable String machineId,
            @RequestParam(required = false) String username,
            @RequestParam(required = false) String password) {

        Optional<Machine> opt = machineService.findById(machineId);
        if (opt.isEmpty()) return notFound("Makine bulunamadı: " + machineId);

        Machine m      = opt.get();
        String[] creds = resolveCreds(m, username, password);
        if (creds == null) return badRequest("Kimlik bilgisi gerekli");

        try {
            List<PropertyEntry> props = sshService.readProperties(m.getHost(), m.getPort(), creds[0], creds[1], m.getSudoUser());
            List<Map<String, String>> list = props.stream()
                    .map(p -> Map.of("key", p.getKey(), "value", p.getValue()))
                    .toList();
            return ok(Map.of("properties", list, "machine", safeView(m)));
        } catch (JSchException e) {
            return authOrSsh(e);
        } catch (Exception e) {
            return serverError(e.getMessage());
        }
    }

    // ─── Property ekleme ───────────────────────────────────────────────────────

    @PostMapping("/properties/{machineId}/add")
    public ResponseEntity<Map<String, Object>> addProperty(
            @PathVariable String machineId,
            @RequestBody Map<String, String> body) {

        Optional<Machine> opt = machineService.findById(machineId);
        if (opt.isEmpty()) return notFound("Makine bulunamadı: " + machineId);

        Machine m      = opt.get();
        String[] creds = resolveCreds(m, body.get("username"), body.get("password"));
        if (creds == null) return badRequest("Kimlik bilgisi gerekli");

        String key   = nvl(body.get("key")).strip();
        String value = nvl(body.get("value")).strip();
        if (key.isEmpty()) return badRequest("Key boş olamaz");

        try {
            sshService.addProperty(m.getHost(), m.getPort(), creds[0], creds[1], m.getSudoUser(), key, value);
            return ok(Map.of("message", "'" + key + "' eklendi"));
        } catch (IllegalArgumentException e) {
            return conflict(e.getMessage());
        } catch (JSchException e) {
            return authOrSsh(e);
        } catch (Exception e) {
            return serverError(e.getMessage());
        }
    }

    // ─── Property güncelleme ───────────────────────────────────────────────────

    @PutMapping("/properties/{machineId}")
    public ResponseEntity<Map<String, Object>> updateProperty(
            @PathVariable String machineId,
            @RequestBody Map<String, String> body) {

        Optional<Machine> opt = machineService.findById(machineId);
        if (opt.isEmpty()) return notFound("Makine bulunamadı: " + machineId);

        Machine m      = opt.get();
        String[] creds = resolveCreds(m, body.get("username"), body.get("password"));
        if (creds == null) return badRequest("Kimlik bilgisi gerekli");

        String key   = nvl(body.get("key")).strip();
        String value = nvl(body.get("value")).strip();
        if (key.isEmpty()) return badRequest("Key boş olamaz");

        try {
            sshService.updateProperty(m.getHost(), m.getPort(), creds[0], creds[1], m.getSudoUser(), key, value);
            return ok(Map.of("message", "'" + key + "' güncellendi"));
        } catch (IllegalArgumentException e) {
            return notFound(e.getMessage());
        } catch (JSchException e) {
            return authOrSsh(e);
        } catch (Exception e) {
            return serverError(e.getMessage());
        }
    }

    // ─── Property silme ────────────────────────────────────────────────────────

    @DeleteMapping("/properties/{machineId}")
    public ResponseEntity<Map<String, Object>> deleteProperty(
            @PathVariable String machineId,
            @RequestBody Map<String, String> body) {

        Optional<Machine> opt = machineService.findById(machineId);
        if (opt.isEmpty()) return notFound("Makine bulunamadı: " + machineId);

        Machine m      = opt.get();
        String[] creds = resolveCreds(m, body.get("username"), body.get("password"));
        if (creds == null) return badRequest("Kimlik bilgisi gerekli");

        String key = nvl(body.get("key")).strip();
        if (key.isEmpty()) return badRequest("Key boş olamaz");

        try {
            sshService.deleteProperty(m.getHost(), m.getPort(), creds[0], creds[1], m.getSudoUser(), key);
            return ok(Map.of("message", "'" + key + "' silindi"));
        } catch (IllegalArgumentException e) {
            return notFound(e.getMessage());
        } catch (JSchException e) {
            return authOrSsh(e);
        } catch (Exception e) {
            return serverError(e.getMessage());
        }
    }

    // ─── Servis durumu ─────────────────────────────────────────────────────────

    @GetMapping("/service/{machineId}/status")
    public ResponseEntity<Map<String, Object>> serviceStatus(
            @PathVariable String machineId,
            @RequestParam(required = false) String username,
            @RequestParam(required = false) String password) {

        Optional<Machine> opt = machineService.findById(machineId);
        if (opt.isEmpty()) return notFound("Makine bulunamadı: " + machineId);

        Machine m      = opt.get();
        String[] creds = resolveCreds(m, username, password);
        if (creds == null) return badRequest("Kimlik bilgisi gerekli");

        try {
            String status = sshService.getServiceStatus(
                    m.getHost(), m.getPort(), creds[0], creds[1], m.getServiceName());
            return ok(Map.of("status", status, "serviceName", m.getServiceName()));
        } catch (JSchException e) {
            return authOrSsh(e);
        } catch (Exception e) {
            return serverError(e.getMessage());
        }
    }

    // ─── Servis başlat ─────────────────────────────────────────────────────────

    @PostMapping("/service/{machineId}/start")
    public ResponseEntity<Map<String, Object>> serviceStart(
            @PathVariable String machineId,
            @RequestBody(required = false) Map<String, String> body) {

        return serviceAction(machineId, "start", body);
    }

    @PostMapping("/service/{machineId}/stop")
    public ResponseEntity<Map<String, Object>> serviceStop(
            @PathVariable String machineId,
            @RequestBody(required = false) Map<String, String> body) {

        return serviceAction(machineId, "stop", body);
    }

    @PostMapping("/service/{machineId}/restart")
    public ResponseEntity<Map<String, Object>> serviceRestart(
            @PathVariable String machineId,
            @RequestBody(required = false) Map<String, String> body) {

        return serviceAction(machineId, "restart", body);
    }

    private ResponseEntity<Map<String, Object>> serviceAction(
            String machineId, String action, Map<String, String> body) {

        Optional<Machine> opt = machineService.findById(machineId);
        if (opt.isEmpty()) return notFound("Makine bulunamadı: " + machineId);

        Machine m = opt.get();
        Map<String, String> b = body != null ? body : Map.of();
        String[] creds = resolveCreds(m, b.get("username"), b.get("password"));
        if (creds == null) return badRequest("Kimlik bilgisi gerekli");

        try {
            switch (action) {
                case "start"   -> sshService.startService(m.getHost(), m.getPort(), creds[0], creds[1], m.getServiceName());
                case "stop"    -> sshService.stopService(m.getHost(), m.getPort(), creds[0], creds[1], m.getServiceName());
                case "restart" -> sshService.restartService(m.getHost(), m.getPort(), creds[0], creds[1], m.getServiceName());
            }
            // İşlem sonrası yeni durumu sorgula
            String status = sshService.getServiceStatus(
                    m.getHost(), m.getPort(), creds[0], creds[1], m.getServiceName());
            return ok(Map.of(
                    "message",     m.getServiceName() + " " + action + " komutu gönderildi",
                    "status",      status,
                    "serviceName", m.getServiceName()
            ));
        } catch (JSchException e) {
            return authOrSsh(e);
        } catch (Exception e) {
            return serverError(e.getMessage());
        }
    }

    // ─── Yardımcılar ───────────────────────────────────────────────────────────

    private String[] resolveCreds(Machine m, String reqUser, String reqPass) {
        if (m.isHasCredentials()) return new String[]{m.getUsername(), m.getPassword()};
        if (reqUser != null && !reqUser.isBlank() && reqPass != null && !reqPass.isBlank())
            return new String[]{reqUser, reqPass};
        return null;
    }

    private Map<String, Object> safeView(Machine m) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id",             m.getId());
        map.put("name",           m.getName());
        map.put("host",           m.getHost());
        map.put("port",           m.getPort());
        map.put("environment",    m.getEnvironment());
        map.put("description",    m.getDescription());
        map.put("serviceName",    m.getServiceName());
        map.put("hasCredentials", m.isHasCredentials());
        map.put("sudoUser",       m.getSudoUser());
        return map;
    }

    private boolean isAuthError(JSchException e) {
        String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
        return msg.contains("auth") || msg.contains("authentication");
    }

    private ResponseEntity<Map<String, Object>> authOrSsh(JSchException e) {
        boolean auth = isAuthError(e);
        return ResponseEntity.status(auth ? 401 : 503)
                .body(error(auth ? "Kullanıcı adı veya şifre hatalı" : "SSH hatası: " + e.getMessage()));
    }

    private String nvl(String s)                          { return s == null ? "" : s; }
    private Map<String, Object> error(String msg)         { return Map.of("success", false, "error", msg); }

    private ResponseEntity<Map<String, Object>> ok(Map<String, Object> extra) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.putAll(extra);
        return ResponseEntity.ok(body);
    }
    private ResponseEntity<Map<String, Object>> badRequest(String m) { return ResponseEntity.badRequest().body(error(m)); }
    private ResponseEntity<Map<String, Object>> notFound(String m)   { return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error(m)); }
    private ResponseEntity<Map<String, Object>> conflict(String m)   { return ResponseEntity.status(HttpStatus.CONFLICT).body(error(m)); }
    private ResponseEntity<Map<String, Object>> serverError(String m){ return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error(m)); }
}

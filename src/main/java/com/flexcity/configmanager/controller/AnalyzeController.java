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
@RequestMapping("/api/analyze")
public class AnalyzeController {

    private static final Logger log = LoggerFactory.getLogger(AnalyzeController.class);

    private final MachineService machineService;
    private final SshService     sshService;

    public AnalyzeController(MachineService machineService, SshService sshService) {
        this.machineService = machineService;
        this.sshService     = sshService;
    }

    // ─── Java PID ─────────────────────────────────────────────────────────────

    @GetMapping("/{machineId}/pid")
    public ResponseEntity<Map<String, Object>> getPid(@PathVariable String machineId) {
        Optional<Machine> opt = machineService.findById(machineId);
        if (opt.isEmpty()) return notFound("Makine bulunamadı");
        Machine m = opt.get();
        String[] creds = creds(m);
        if (creds == null) return badRequest("Kimlik bilgisi eksik");

        try {
            String pid = sshService.getJavaPid(m.getHost(), m.getPort(), creds[0], creds[1], m.getServiceName());
            if (pid.isBlank()) return ResponseEntity.ok(Map.of("success", false, "error", "Java process bulunamadı"));
            return ResponseEntity.ok(Map.of("success", true, "pid", pid));
        } catch (Exception e) {
            return serverError(e.getMessage());
        }
    }

    // ─── Ana process bilgisi ──────────────────────────────────────────────────

    @GetMapping("/{machineId}/process")
    public ResponseEntity<Map<String, Object>> getProcessInfo(
            @PathVariable String machineId,
            @RequestParam(required = false) String pid) {

        Optional<Machine> opt = machineService.findById(machineId);
        if (opt.isEmpty()) return notFound("Makine bulunamadı");
        Machine m = opt.get();
        String[] creds = creds(m);
        if (creds == null) return badRequest("Kimlik bilgisi eksik");

        try {
            String resolvedPid = pid;
            if (resolvedPid == null || resolvedPid.isBlank()) {
                resolvedPid = sshService.getJavaPid(m.getHost(), m.getPort(), creds[0], creds[1], m.getServiceName());
            }
            if (resolvedPid == null || resolvedPid.isBlank()) {
                return ResponseEntity.ok(Map.of("success", false, "error", "Java process bulunamadı"));
            }
            Map<String, String> info = sshService.getProcessInfo(
                    m.getHost(), m.getPort(), creds[0], creds[1], resolvedPid);
            Map<String, Object> resp = new LinkedHashMap<>(info);
            resp.put("success", true);
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            return serverError(e.getMessage());
        }
    }

    // ─── Sıcak thread'ler (top -H -p PID) ────────────────────────────────────

    @GetMapping("/{machineId}/threads")
    public ResponseEntity<Map<String, Object>> getThreads(
            @PathVariable String machineId,
            @RequestParam(required = false) String pid) {

        Optional<Machine> opt = machineService.findById(machineId);
        if (opt.isEmpty()) return notFound("Makine bulunamadı");
        Machine m = opt.get();
        String[] creds = creds(m);
        if (creds == null) return badRequest("Kimlik bilgisi eksik");

        try {
            String resolvedPid = pid;
            if (resolvedPid == null || resolvedPid.isBlank()) {
                resolvedPid = sshService.getJavaPid(m.getHost(), m.getPort(), creds[0], creds[1], m.getServiceName());
            }
            if (resolvedPid == null || resolvedPid.isBlank()) {
                return ResponseEntity.ok(Map.of("success", false, "error", "Java process bulunamadı"));
            }

            List<Map<String, String>> threads = sshService.getHotThreads(
                    m.getHost(), m.getPort(), creds[0], creds[1], resolvedPid);

            return ResponseEntity.ok(Map.of("success", true, "pid", resolvedPid, "threads", threads));
        } catch (Exception e) {
            log.warn("[Analyze] threads hatası [{}]: {}", m.getName(), e.getMessage());
            return serverError(e.getMessage());
        }
    }

    // ─── Thread dump (jstack) ─────────────────────────────────────────────────

    @GetMapping("/{machineId}/dump")
    public ResponseEntity<Map<String, Object>> getThreadDump(
            @PathVariable String machineId,
            @RequestParam(required = false) String pid) {

        Optional<Machine> opt = machineService.findById(machineId);
        if (opt.isEmpty()) return notFound("Makine bulunamadı");
        Machine m = opt.get();
        String[] creds = creds(m);
        if (creds == null) return badRequest("Kimlik bilgisi eksik");

        try {
            String resolvedPid = pid;
            if (resolvedPid == null || resolvedPid.isBlank()) {
                resolvedPid = sshService.getJavaPid(m.getHost(), m.getPort(), creds[0], creds[1], m.getServiceName());
            }
            if (resolvedPid == null || resolvedPid.isBlank()) {
                return ResponseEntity.ok(Map.of("success", false, "error", "Java process bulunamadı"));
            }

            String dump = sshService.getThreadDump(
                    m.getHost(), m.getPort(), creds[0], creds[1],
                    resolvedPid, m.resolvedJstack());

            return ResponseEntity.ok(Map.of("success", true, "pid", resolvedPid, "dump", dump));
        } catch (Exception e) {
            log.warn("[Analyze] dump hatası [{}]: {}", m.getName(), e.getMessage());
            return serverError(e.getMessage());
        }
    }

    // ─── Yardımcılar ─────────────────────────────────────────────────────────

    private String[] creds(Machine m) {
        if (m.isHasCredentials()) return new String[]{m.getUsername(), m.getPassword()};
        return null;
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

package com.flexcity.configmanager.controller;

import com.flexcity.configmanager.model.ApprovalRequest;
import com.flexcity.configmanager.model.ApprovalRequest.Action;
import com.flexcity.configmanager.model.Machine;
import com.flexcity.configmanager.service.ApprovalService;
import com.flexcity.configmanager.service.MachineService;
import com.flexcity.configmanager.service.SshService;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.format.DateTimeFormatter;
import java.util.*;

@RestController
@RequestMapping("/api/approval")
public class ApprovalController {

    private static final Logger log = LoggerFactory.getLogger(ApprovalController.class);
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("HH:mm");

    private final ApprovalService approvalService;
    private final MachineService  machineService;
    private final SshService      sshService;

    public ApprovalController(ApprovalService approvalService,
                               MachineService machineService,
                               SshService sshService) {
        this.approvalService = approvalService;
        this.machineService  = machineService;
        this.sshService      = sshService;
    }

    // ─── Onay talebi oluştur (normal kullanıcı) ───────────────────────────────

    @PostMapping("/request")
    public ResponseEntity<Map<String, Object>> createRequest(
            @RequestBody Map<String, String> body,
            HttpSession session) {

        String machineId = body.get("machineId");
        String actionStr = body.getOrDefault("action", "").toUpperCase();
        String note      = body.getOrDefault("note", "").trim();

        Optional<Machine> opt = machineService.findById(machineId);
        if (opt.isEmpty()) return error("Makine bulunamadı: " + machineId);

        Action action;
        try { action = Action.valueOf(actionStr); }
        catch (Exception e) { return error("Geçersiz işlem: " + actionStr); }

        Machine m   = opt.get();
        ApprovalRequest req = approvalService.create(machineId, m.getName(), action, note);

        log.info("[Approval] Talep oluşturuldu: {} {} {} — id:{}", m.getName(), action, note, req.getId());

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("success",    true);
        resp.put("requestId",  req.getId());
        resp.put("message",    "Onay talebiniz oluşturuldu. Admin onay kodunu size iletecektir.");
        resp.put("expiresAt",  req.getExpiresAt().format(DateTimeFormatter.ofPattern("HH:mm")));
        return ResponseEntity.ok(resp);
    }

    // ─── Bekleyen talepleri listele (SADECE ADMİN) ────────────────────────────

    @GetMapping("/pending")
    public ResponseEntity<Map<String, Object>> listPending(HttpSession session) {
        if (!isAdmin(session)) return forbidden();

        List<ApprovalRequest> pending = approvalService.listPending();
        List<Map<String, Object>> list = pending.stream().map(this::toView).toList();
        return ResponseEntity.ok(Map.of("success", true, "requests", list));
    }

    // ─── Kodu gir → işlemi çalıştır (normal kullanıcı) ──────────────────────

    @PostMapping("/execute")
    public ResponseEntity<Map<String, Object>> execute(
            @RequestBody Map<String, String> body,
            HttpSession session) {

        String requestId = body.get("requestId");
        String code      = body.getOrDefault("code", "").trim();

        if (requestId == null || code.isBlank()) return error("requestId ve code zorunlu");

        Optional<ApprovalRequest> opt = approvalService.execute(requestId, code);
        if (opt.isEmpty()) {
            // Talebi bul — durumuna göre mesaj ver
            Optional<ApprovalRequest> reqOpt = approvalService.findById(requestId);
            if (reqOpt.isPresent()) {
                ApprovalRequest.Status s = reqOpt.get().getStatus();
                String msg = switch (s) {
                    case EXPIRED  -> "Onay kodu süresi doldu (5 dakika). Yeni talep oluşturun.";
                    case REJECTED -> "Bu talep admin tarafından reddedildi.";
                    case EXECUTED -> "Bu talep zaten işleme alındı.";
                    default       -> "Onay kodu hatalı.";
                };
                return error(msg);
            }
            return error("Geçersiz veya hatalı onay kodu.");
        }

        ApprovalRequest req = opt.get();
        Optional<Machine> machOpt = machineService.findById(req.getMachineId());
        if (machOpt.isEmpty()) return error("Makine bulunamadı.");

        Machine m = machOpt.get();
        String[] creds = m.isHasCredentials()
                ? new String[]{m.getUsername(), m.getPassword()} : null;
        if (creds == null) return error("Makine kimlik bilgisi eksik.");

        try {
            switch (req.getAction()) {
                case START   -> sshService.startService(m.getHost(), m.getPort(), creds[0], creds[1], m.getServiceName());
                case STOP    -> sshService.stopService(m.getHost(), m.getPort(), creds[0], creds[1], m.getServiceName());
                case RESTART -> sshService.restartService(m.getHost(), m.getPort(), creds[0], creds[1], m.getServiceName());
            }
            String newStatus = sshService.getServiceStatus(m.getHost(), m.getPort(), creds[0], creds[1], m.getServiceName());
            log.info("[Approval] İşlem gerçekleşti: {} {} → {}", m.getName(), req.getAction(), newStatus);
            return ResponseEntity.ok(Map.of(
                    "success",     true,
                    "message",     m.getName() + " — " + actionLabel(req.getAction()) + " işlemi başarıyla gerçekleşti.",
                    "status",      newStatus,
                    "serviceName", m.getServiceName()
            ));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("success", false, "error", "SSH hatası: " + e.getMessage()));
        }
    }

    // ─── Talebi reddet (SADECE ADMİN) ────────────────────────────────────────

    @PostMapping("/reject/{requestId}")
    public ResponseEntity<Map<String, Object>> reject(
            @PathVariable String requestId,
            HttpSession session) {

        if (!isAdmin(session)) return forbidden();

        boolean ok = approvalService.reject(requestId);
        if (!ok) return error("Talep bulunamadı veya zaten işleme alınmış.");
        log.info("[Approval] Talep reddedildi: {}", requestId);
        return ResponseEntity.ok(Map.of("success", true, "message", "Talep reddedildi."));
    }

    // ─── Yardımcılar ─────────────────────────────────────────────────────────

    private boolean isAdmin(HttpSession session) {
        return Boolean.TRUE.equals(session.getAttribute("isAdmin"));
    }

    private Map<String, Object> toView(ApprovalRequest r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",          r.getId());
        m.put("machineId",   r.getMachineId());
        m.put("machineName", r.getMachineName());
        m.put("action",      r.getAction().name());
        m.put("actionLabel", actionLabel(r.getAction()));
        m.put("note",        r.getNote());
        m.put("code",        r.getCode());          // Sadece admin panelinde görünür
        m.put("requestedAt", r.getRequestedAt().format(FMT));
        m.put("expiresAt",   r.getExpiresAt().format(FMT));
        m.put("status",      r.getStatus().name());
        return m;
    }

    private String actionLabel(Action a) {
        return switch (a) {
            case START   -> "Başlat";
            case STOP    -> "Durdur";
            case RESTART -> "Yeniden Başlat";
        };
    }

    private ResponseEntity<Map<String, Object>> error(String msg) {
        return ResponseEntity.badRequest().body(Map.of("success", false, "error", msg));
    }

    private ResponseEntity<Map<String, Object>> forbidden() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("success", false, "error", "Bu işlem için admin yetkisi gerekli."));
    }
}

package com.flexcity.configmanager.controller;

import com.flexcity.configmanager.model.Machine;
import com.flexcity.configmanager.service.MachineService;
import com.flexcity.configmanager.service.SshService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * SSE üzerinden canlı log akışı.
 * GET /api/logs/{machineId}  — text/event-stream
 *
 * Kimlik çözümleme ApiController ile aynı mantık:
 *   machines.json'da username+password varsa → JSON kullanılır
 *   Yoksa → ?username=...&password=... query param beklenir
 */
@RestController
@RequestMapping("/api")
public class LogController {

    private static final Logger log = LoggerFactory.getLogger(LogController.class);

    private final MachineService machineService;
    private final SshService     sshService;
    // Her bağlantı için ayrı thread — tail -f sürekli çalışır
    private final ExecutorService executor = Executors.newCachedThreadPool();

    public LogController(MachineService machineService, SshService sshService) {
        this.machineService = machineService;
        this.sshService     = sshService;
    }

    @GetMapping(value = "/logs/{machineId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamLogs(
            @PathVariable String machineId,
            @RequestParam(required = false) String username,
            @RequestParam(required = false) String password,
            @RequestParam(defaultValue = "1000") int lines) {

        // 0L = timeout yok; bağlantı kopana kadar açık kalır
        SseEmitter emitter = new SseEmitter(0L);

        Optional<Machine> opt = machineService.findById(machineId);
        if (opt.isEmpty()) {
            completeWithError(emitter, "Makine bulunamadı: " + machineId);
            return emitter;
        }

        Machine m = opt.get();
        String[] creds = resolveCreds(m, username, password);
        if (creds == null) {
            completeWithError(emitter, "Kimlik bilgisi gerekli");
            return emitter;
        }

        if (!m.hasLogFile()) {
            completeWithError(emitter, "Bu makine için logFile tanımlı değil");
            return emitter;
        }

        // lines parametresini güvenli aralıkta tut (0 = sadece canlı, max 10000)
        final int historyLines = Math.max(0, Math.min(lines, 10000));

        AtomicBoolean stopped   = new AtomicBoolean(false);
        // Geçmiş satırların bitip canlıya geçildiğini takip et
        // tail -n X -f önce X satır basar, sonra yeni gelenler akar.
        // İlk historyLines satıra kadar "hist" event'i, sonrasına "message" gönderilir.
        java.util.concurrent.atomic.AtomicInteger sentCount = new java.util.concurrent.atomic.AtomicInteger(0);

        emitter.onCompletion(() -> stopped.set(true));
        emitter.onTimeout(()    -> stopped.set(true));
        emitter.onError(e       -> stopped.set(true));

        final String user  = creds[0];
        final String pass  = creds[1];

        executor.submit(() -> {
            try {
                log.info("Log stream başladı: {} -> {} (geçmiş: {} satır)", m.getName(), m.getLogFile(), historyLines);
                // Önce metadata gönder
                emitter.send(SseEmitter.event().name("meta")
                        .data("{\"historyLines\":" + historyLines + "}"));

                sshService.tailLog(
                        m.getHost(), m.getPort(), user, pass,
                        m.getSudoUser(), m.getLogFile(),
                        historyLines,
                        line -> {
                            try {
                                int n = sentCount.incrementAndGet();
                                // historyLines > 0 ise ilk N satır geçmişten geldi → "hist" event
                                String eventName = (historyLines > 0 && n <= historyLines) ? "hist" : "message";
                                emitter.send(SseEmitter.event().name(eventName).data(line));
                            } catch (Exception ex) {
                                stopped.set(true);
                            }
                        },
                        stopped
                );
                emitter.complete();
            } catch (Exception e) {
                log.warn("Log stream hatası [{}]: {}", m.getName(), e.getMessage());
                try {
                    emitter.send(SseEmitter.event().name("sse-error").data(e.getMessage()));
                } catch (Exception ignored) {}
                emitter.complete();
            }
        });

        return emitter;
    }

    // ─── Yardımcılar ───────────────────────────────────────────────────────────

    private String[] resolveCreds(Machine m, String reqUser, String reqPass) {
        if (m.isHasCredentials()) return new String[]{m.getUsername(), m.getPassword()};
        if (reqUser != null && !reqUser.isBlank() && reqPass != null && !reqPass.isBlank())
            return new String[]{reqUser, reqPass};
        return null;
    }

    private void completeWithError(SseEmitter emitter, String msg) {
        try {
            emitter.send(SseEmitter.event().name("sse-error").data(msg));
        } catch (Exception ignored) {}
        emitter.complete();
    }
}

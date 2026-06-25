package com.flexcity.configmanager.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flexcity.configmanager.model.Machine;
import com.flexcity.configmanager.service.MachineService;
import com.flexcity.configmanager.service.SshService;
import com.jcraft.jsch.Channel;
import com.jcraft.jsch.ChannelShell;
import com.jcraft.jsch.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class TerminalWebSocketHandler extends AbstractWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(TerminalWebSocketHandler.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private final MachineService machineService;
    private final SshService     sshService;

    // Her WebSocket session'ı için SSH oturumu
    private final Map<String, TerminalSession> sessions = new ConcurrentHashMap<>();

    public TerminalWebSocketHandler(MachineService machineService, SshService sshService) {
        this.machineService = machineService;
        this.sshService     = sshService;
    }

    // ── Bağlantı açıldı ──────────────────────────────────────────────────────
    @Override
    public void afterConnectionEstablished(WebSocketSession ws) {
        log.info("[Terminal] WS bağlantısı açıldı: {}", ws.getId());
    }

    // ── Mesaj geldi ──────────────────────────────────────────────────────────
    @Override
    protected void handleTextMessage(WebSocketSession ws, TextMessage msg) throws Exception {
        String payload = msg.getPayload();

        // İlk mesaj JSON handshake mi kontrol et
        if (payload.startsWith("{")) {
            try {
                JsonNode node = mapper.readTree(payload);
                String type = node.has("type") ? node.get("type").asText() : "";

                if ("connect".equals(type)) {
                    handleConnect(ws, node);
                    return;
                }
                if ("resize".equals(type)) {
                    handleResize(ws, node);
                    return;
                }
            } catch (Exception ignored) {
                // JSON değil, normal input olarak devam et
            }
        }

        // Normal terminal input → SSH'a yaz
        TerminalSession ts = sessions.get(ws.getId());
        if (ts != null && ts.stdin != null) {
            ts.stdin.write(payload.getBytes("UTF-8"));
            ts.stdin.flush();
        }
    }

    // ── Bağlantı kapandı ─────────────────────────────────────────────────────
    @Override
    public void afterConnectionClosed(WebSocketSession ws, CloseStatus status) {
        cleanup(ws.getId());
        log.info("[Terminal] WS bağlantısı kapandı: {} ({})", ws.getId(), status);
    }

    @Override
    public void handleTransportError(WebSocketSession ws, Throwable ex) {
        cleanup(ws.getId());
        log.warn("[Terminal] WS hatası {}: {}", ws.getId(), ex.getMessage());
    }

    // ── Connect handshake ────────────────────────────────────────────────────
    private void handleConnect(WebSocketSession ws, JsonNode node) {
        String machineId = node.has("machineId") ? node.get("machineId").asText() : null;
        String username  = node.has("username")  ? node.get("username").asText()  : null;
        String password  = node.has("password")  ? node.get("password").asText()  : null;
        int    cols      = node.has("cols")       ? node.get("cols").asInt(80)     : 80;
        int    rows      = node.has("rows")       ? node.get("rows").asInt(24)     : 24;

        if (machineId == null) {
            sendError(ws, "machineId eksik");
            return;
        }

        Optional<Machine> opt = machineService.findById(machineId);
        if (opt.isEmpty()) {
            sendError(ws, "Makine bulunamadı: " + machineId);
            return;
        }

        Machine m = opt.get();

        // Kimlik bilgisi çözümle
        String user, pass;
        if (m.isHasCredentials()) {
            user = m.getUsername();
            pass = m.getPassword();
        } else if (username != null && !username.isBlank() && password != null) {
            user = username;
            pass = password;
        } else {
            sendError(ws, "Kimlik bilgisi gerekli");
            return;
        }

        try {
            log.info("[Terminal] SSH bağlanıyor: {} {}:{}", m.getName(), m.getHost(), m.getPort());

            Session sshSession = sshService.openSession(m.getHost(), m.getPort(), user, pass);

            ChannelShell channel = (ChannelShell) sshSession.openChannel("shell");
            channel.setPtyType("xterm-256color");
            channel.setPtySize(cols, rows, cols * 8, rows * 16);

            InputStream  stdout = channel.getInputStream();
            OutputStream stdin  = channel.getOutputStream();

            channel.connect(10_000);

            TerminalSession ts = new TerminalSession(sshSession, channel, stdin);
            sessions.put(ws.getId(), ts);

            // Çıkışı oku ve WebSocket'e aktar
            Thread readerThread = new Thread(() -> {
                byte[] buf = new byte[4096];
                try {
                    int n;
                    while (!channel.isClosed() && (n = stdout.read(buf)) != -1) {
                        if (ws.isOpen()) {
                            String chunk = new String(buf, 0, n, "UTF-8");
                            synchronized (ws) {
                                ws.sendMessage(new TextMessage(chunk));
                            }
                        }
                    }
                } catch (IOException e) {
                    if (ws.isOpen()) log.debug("[Terminal] Okuma bitti: {}", e.getMessage());
                } finally {
                    cleanup(ws.getId());
                    try { if (ws.isOpen()) ws.close(CloseStatus.NORMAL); } catch (Exception ignored) {}
                }
            }, "terminal-reader-" + ws.getId());
            readerThread.setDaemon(true);
            readerThread.start();

            // Bağlantı başarılı bildir
            sendJson(ws, Map.of("type", "connected", "machine", m.getName(), "host", m.getHost()));
            log.info("[Terminal] Shell açıldı: {} {}:{}", m.getName(), m.getHost(), m.getPort());

        } catch (Exception e) {
            log.error("[Terminal] Bağlantı hatası [{}]: {}", m.getName(), e.getMessage());
            sendError(ws, "SSH bağlantısı başarısız: " + e.getMessage());
        }
    }

    // ── PTY boyutu değiştir ───────────────────────────────────────────────────
    private void handleResize(WebSocketSession ws, JsonNode node) {
        TerminalSession ts = sessions.get(ws.getId());
        if (ts == null) return;
        int cols = node.has("cols") ? node.get("cols").asInt(80) : 80;
        int rows = node.has("rows") ? node.get("rows").asInt(24) : 24;
        ((ChannelShell) ts.channel).setPtySize(cols, rows, cols * 8, rows * 16);
    }

    // ── Temizlik ─────────────────────────────────────────────────────────────
    private void cleanup(String wsId) {
        TerminalSession ts = sessions.remove(wsId);
        if (ts != null) {
            try { ts.channel.disconnect(); }    catch (Exception ignored) {}
            try { ts.sshSession.disconnect(); } catch (Exception ignored) {}
        }
    }

    // ── Yardımcı metodlar ────────────────────────────────────────────────────
    private void sendError(WebSocketSession ws, String msg) {
        sendJson(ws, Map.of("type", "error", "message", msg));
    }

    private void sendJson(WebSocketSession ws, Object obj) {
        try {
            synchronized (ws) {
                ws.sendMessage(new TextMessage(mapper.writeValueAsString(obj)));
            }
        } catch (Exception e) {
            log.warn("[Terminal] JSON gönderilemedi: {}", e.getMessage());
        }
    }

    // ── İç sınıf ─────────────────────────────────────────────────────────────
    private static class TerminalSession {
        final Session     sshSession;
        final Channel     channel;
        final OutputStream stdin;

        TerminalSession(Session sshSession, Channel channel, OutputStream stdin) {
            this.sshSession = sshSession;
            this.channel    = channel;
            this.stdin      = stdin;
        }
    }
}

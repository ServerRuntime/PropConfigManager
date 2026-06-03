package com.flexcity.configmanager.service;

import com.flexcity.configmanager.model.ApprovalRequest;
import com.flexcity.configmanager.model.ApprovalRequest.Action;
import com.flexcity.configmanager.model.ApprovalRequest.Status;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class ApprovalService {

    // id → ApprovalRequest (thread-safe)
    private final Map<String, ApprovalRequest> requests = new ConcurrentHashMap<>();
    private final SecureRandom rng = new SecureRandom();

    /** Yeni onay talebi oluştur. requestId döner (kod döndürülmez — sadece admin görür). */
    public ApprovalRequest create(String machineId, String machineName,
                                  Action action, String note) {
        String id   = UUID.randomUUID().toString();
        String code = String.format("%06d", rng.nextInt(1_000_000));
        ApprovalRequest req = new ApprovalRequest(id, machineId, machineName, action, note, code);
        requests.put(id, req);
        return req;
    }

    /** Admin: tüm PENDING (ve süresi dolmamış) talepleri listele. */
    public List<ApprovalRequest> listPending() {
        expireOld();
        return requests.values().stream()
                .filter(r -> r.getStatus() == Status.PENDING)
                .sorted(Comparator.comparing(ApprovalRequest::getRequestedAt).reversed())
                .collect(Collectors.toList());
    }

    /** Kullanıcı kodu girer — doğruysa EXECUTED işaretle ve talebi döndür (action için). */
    public Optional<ApprovalRequest> execute(String requestId, String code) {
        expireOld();
        ApprovalRequest req = requests.get(requestId);
        if (req == null)                         return Optional.empty();
        if (req.getStatus() != Status.PENDING)   return Optional.empty();
        if (req.isExpired()) {
            req.setStatus(Status.EXPIRED);
            return Optional.empty();
        }
        if (!req.getCode().equals(code.trim()))  return Optional.empty();
        req.setStatus(Status.EXECUTED);
        return Optional.of(req);
    }

    /** Admin: talebi reddet. */
    public boolean reject(String requestId) {
        ApprovalRequest req = requests.get(requestId);
        if (req == null || req.getStatus() != Status.PENDING) return false;
        req.setStatus(Status.REJECTED);
        return true;
    }

    /** Süresi dolmuş PENDING talepleri EXPIRED yap (temizlik). */
    private void expireOld() {
        requests.values().forEach(r -> {
            if (r.getStatus() == Status.PENDING && r.isExpired()) {
                r.setStatus(Status.EXPIRED);
            }
        });
    }

    public Optional<ApprovalRequest> findById(String id) {
        return Optional.ofNullable(requests.get(id));
    }
}

package com.flexcity.configmanager.model;

import java.time.LocalDateTime;

public class ApprovalRequest {

    public enum Status { PENDING, EXECUTED, REJECTED, EXPIRED }
    public enum Action  { START, STOP, RESTART }

    private final String        id;
    private final String        machineId;
    private final String        machineName;
    private final Action        action;
    private final String        note;
    private final String        code;          // 6 haneli onay kodu — sadece admin görür
    private final LocalDateTime requestedAt;
    private final LocalDateTime expiresAt;     // requestedAt + 5 dakika
    private volatile Status     status;

    public ApprovalRequest(String id, String machineId, String machineName,
                           Action action, String note, String code) {
        this.id          = id;
        this.machineId   = machineId;
        this.machineName = machineName;
        this.action      = action;
        this.note        = note;
        this.code        = code;
        this.requestedAt = LocalDateTime.now();
        this.expiresAt   = this.requestedAt.plusMinutes(5);
        this.status      = Status.PENDING;
    }

    public boolean isExpired() {
        return LocalDateTime.now().isAfter(expiresAt);
    }

    // ─── Getters ───────────────────────────────────────────────────────────────
    public String        getId()          { return id; }
    public String        getMachineId()   { return machineId; }
    public String        getMachineName() { return machineName; }
    public Action        getAction()      { return action; }
    public String        getNote()        { return note; }
    public String        getCode()        { return code; }
    public LocalDateTime getRequestedAt() { return requestedAt; }
    public LocalDateTime getExpiresAt()   { return expiresAt; }
    public Status        getStatus()      { return status; }
    public void          setStatus(Status s) { this.status = s; }
}

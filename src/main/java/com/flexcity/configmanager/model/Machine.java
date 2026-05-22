package com.flexcity.configmanager.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * machines.json satırı.
 *
 * username / password : opsiyonel — varsa arayüz sormaz.
 * serviceName         : opsiyonel — varsayılan "flexcity" (systemctl için).
 * sudoUser            : opsiyonel — dosya okuma/yazma ve log için sudo kullanıcısı.
 * logFile             : opsiyonel — tail -f ile izlenecek log dosyasının tam yolu.
 */
public class Machine {

    private String id;
    private String name;
    private String host;
    private int    port        = 22;
    private String environment = "production";
    private String description = "";
    private String serviceName = "flexcity";

    /**
     * Opsiyonel: dosya işlemleri için sudo ile geçilecek kullanıcı.
     * Boşsa sudo kullanılmaz, SSH kullanıcısıyla doğrudan erişilir.
     */
    private String sudoUser;

    /**
     * Opsiyonel: canlı log izleme için dosya yolu.
     * Örn: /home/flexcity/java/appservers/apache-tomcat-9.0.73/logs/catalina.out
     */
    private String logFile;

    /** JSON'dan okunur, dışarıya yazılmaz */
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String username;

    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String password;

    // ── Getters / Setters ─────────────────────────────────────────────────────

    public String getId()              { return id; }
    public void   setId(String id)     { this.id = id; }

    public String getName()              { return name; }
    public void   setName(String name)   { this.name = name; }

    public String getHost()              { return host; }
    public void   setHost(String host)   { this.host = host; }

    public int  getPort()            { return port; }
    public void setPort(int port)    { this.port = port; }

    public String getEnvironment()                   { return environment; }
    public void   setEnvironment(String environment) { this.environment = environment; }

    public String getDescription()                   { return description; }
    public void   setDescription(String description) { this.description = description; }

    public String getServiceName()                   { return serviceName; }
    public void   setServiceName(String serviceName) { this.serviceName = serviceName != null ? serviceName : "flexcity"; }

    public String getSudoUser()                  { return sudoUser; }
    public void   setSudoUser(String sudoUser)   { this.sudoUser = sudoUser; }

    public boolean hasSudoUser() {
        return sudoUser != null && !sudoUser.isBlank();
    }

    public String getLogFile()                { return logFile; }
    public void   setLogFile(String logFile)  { this.logFile = logFile; }

    public boolean hasLogFile() {
        return logFile != null && !logFile.isBlank();
    }

    public String getUsername()              { return username; }
    public void   setUsername(String u)      { this.username = u; }

    public String getPassword()              { return password; }
    public void   setPassword(String p)      { this.password = p; }

    /** Frontend'e yalnızca "kimlik bilgisi var mı?" gönderilir */
    public boolean isHasCredentials() {
        return username != null && !username.isBlank()
            && password != null && !password.isBlank();
    }
}

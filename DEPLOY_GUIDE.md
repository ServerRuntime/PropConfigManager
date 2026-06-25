# javaarge.ibb.gov.tr — Uygulama Deploy Rehberi

## Sunucu Bilgileri

| Alan | Değer |
|------|-------|
| Hostname | javaarge.ibb.gov.tr |
| İşletim Sistemi | Red Hat Enterprise Linux 9.6 (Plow) |
| Kernel | Linux 5.14.0-570.28.1.el9_6.x86_64 |
| Sanallaştırma | VMware (VMware7,1) |
| Docker Root | /data/docker |
| Paket Yöneticisi | dnf |
| Servis Yöneticisi | systemctl |

---

## Mevcut Altyapı

### Çalışan Container'lar

| Container | Image | Port | Açıklama |
|-----------|-------|------|----------|
| nginx-proxy | nginx:alpine | 80 | Tüm uygulamalara reverse proxy |
| portainer | portainer-ce:latest | 8000, 9443 | Docker yönetim paneli |
| rundeck | rundeck:5.1.0 | 4440 | Job otomasyon sistemi |
| rundeck-postgres | postgres:15 | 5433 | Rundeck veritabanı |
| keycloak | keycloak:26.6.1 | 9090 | Kimlik doğrulama |
| keycloak-postgres | postgres:16.2 | 5432 | Keycloak veritabanı |
| redis | redis-stack | 6379, 8001 | Cache |
| postfix | postfix:latest | 587 | Mail servisi |
| ibb-user-maestro | ibb-user-maestro:latest | 8081 | Maestro API |
| soap-client-generator | soap-client-generator:latest | 8085 | WSDL/SOAP Client Üreteci |

### Erişim URL'leri

| Uygulama | URL |
|----------|-----|
| Portainer | http://javaarge.ibb.gov.tr/portainer/ |
| Rundeck | http://javaarge.ibb.gov.tr/rundeck/ |
| Maestro | http://javaarge.ibb.gov.tr/maestro/ |
| SOAP Client Generator | http://javaarge.ibb.gov.tr/soap-client-generator/ |
| Portainer Direkt | https://javaarge.ibb.gov.tr:9443 |

### Nginx Reverse Proxy

Tüm uygulamalar `nginx-proxy` container'ı üzerinden path bazlı yönlendirme ile erişilir.  
Config dosyası: `/etc/nginx/conf.d/portainer.conf` (host üzerinde)

```nginx
server {
    listen 80;
    server_name javaarge.ibb.gov.tr;

    location /portainer/ {
        proxy_pass https://host.docker.internal:9443/;
        proxy_ssl_verify off;
        ...
    }

    location /rundeck/ {
        proxy_pass http://host.docker.internal:4440/rundeck/;
        ...
    }

    location /uygulamaadi/ {
        proxy_pass http://host.docker.internal:PORT/uygulamaadi/;
        ...
    }
}
```

### Docker Registry

Sunucu internete çıkamadığı için tüm Docker image'ları IBB'nin kendi registry'sinden çekilmelidir:

```
repo.ibb.gov.tr/repository/docker-hub/<image>:<tag>
```

**Mevcut image örnekleri:**
- `repo.ibb.gov.tr/repository/docker-hub/eclipse-temurin:25-jre-alpine`
- `repo.ibb.gov.tr/repository/docker-hub/nginx:alpine`
- `repo.ibb.gov.tr/repository/docker-hub/postgres:16.2`

---

## Yeni Uygulama Deploy Adımları

### 1. Boşta Olan Port Belirleme

```bash
sudo docker ps --format "table {{.Names}}\t{{.Ports}}"
```

Mevcut kullanılan portlar: `80, 587, 4440, 5432, 5433, 6379, 8000, 8001, 8081, 8085, 9090, 9443`

Yeni uygulama için `8086, 8087, ...` gibi boş bir port seçin.

---

### 2. Uygulama Hazırlığı (Geliştirici Makinesinde)

#### Spring Boot Uygulaması için application.yml / application.properties

```yaml
server:
  port: 8080                        # Container içi port (her zaman 8080)
  servlet:
    context-path: /uygulamaadi     # nginx path ile aynı olmalı
```

> **Not:** `app.maven-executable` gibi konfigürasyonlar uygulamaya özgüdür, genel bir kural değildir.

#### JAR Derleme

```powershell
cd C:\proje-dizini
mvn package -DskipTests
```

Çıktı: `target/uygulama-1.0.0.jar`

---

### 3. Dockerfile Oluşturma

IBB registry'sindeki image kullanılmalıdır. Java uygulaması için:

```dockerfile
FROM repo.ibb.gov.tr/repository/docker-hub/eclipse-temurin:25-jre-alpine

# Uygulama Maven build gerektiriyorsa JDK ve Maven ekle
RUN apk add --no-cache openjdk25-jdk maven openssl

# IBB SSL sertifikası (Maven ve diğer HTTPS bağlantıları için)
RUN openssl s_client -connect repo.ibb.gov.tr:443 -showcerts </dev/null 2>/dev/null \
    | openssl x509 -outform PEM > /tmp/ibb-cert.pem && \
    keytool -import -noprompt -trustcacerts -alias ibb-nexus \
    -file /tmp/ibb-cert.pem \
    -keystore /usr/lib/jvm/java-25-openjdk/lib/security/cacerts \
    -storepass changeit

# Maven IBB Nexus mirror (internet erişimi yok)
RUN mkdir -p /root/.m2 && printf '<settings>\n  <mirrors>\n    <mirror>\n      <id>ibb-nexus</id>\n      <mirrorOf>*</mirrorOf>\n      <url>https://repo.ibb.gov.tr/repository/maven-public/</url>\n    </mirror>\n  </mirrors>\n</settings>' > /root/.m2/settings.xml

ENV TZ=Europe/Istanbul
ENV JAVA_HOME=/usr/lib/jvm/java-25-openjdk
ENV PATH="$JAVA_HOME/bin:$PATH"

WORKDIR /app
COPY target/uygulama-1.0.0.jar app.jar
EXPOSE 8080

# JAVA_HOME override edilebilmesi için tam path kullanılır
ENTRYPOINT ["/usr/lib/jvm/java-25-openjdk/bin/java", "-jar", "app.jar"]
```

> **Önemli:** Maven gerektirmeyen basit uygulamalar için `RUN apk add` ve sertifika adımlarını atlayabilirsiniz.

---

### 4. docker-compose.yml Oluşturma

```yaml
services:
  uygulamaadi:
    image: uygulamaadi:latest
    container_name: uygulamaadi
    ports:
      - "XXXX:8080"          # XXXX = seçilen boş host port
    restart: unless-stopped
    environment:
      - TZ=Europe/Istanbul
      - JAVA_HOME=/usr/lib/jvm/java-25-openjdk
    healthcheck:
      test: ["CMD-SHELL", "wget -qO- http://localhost:8080/uygulamaadi/ || exit 1"]
      interval: 30s
      timeout: 10s
      retries: 3
      start_period: 40s
```

---

### 5. Sunucuda Dizin Oluşturma

```bash
sudo mkdir -p /opt/uygulamaadi/target
sudo chown <kullanici>:<kullanici> /opt/uygulamaadi
```

---

### 6. Dosyaları Sunucuya Kopyalama

```powershell
# JAR kopyala
scp target\uygulama-1.0.0.jar <kullanici>@javaarge.ibb.gov.tr:/opt/uygulamaadi/target/

# Dockerfile kopyala
scp Dockerfile <kullanici>@javaarge.ibb.gov.tr:/opt/uygulamaadi/
```

---

### 7. Sunucuda Docker Image Build

```bash
cd /opt/uygulamaadi
sudo docker build -t uygulamaadi:latest .
```

---

### 8. Nginx Config Güncelleme

`/etc/nginx/conf.d/portainer.conf` dosyasına yeni `location` bloğu ekleyin:

```bash
sudo nano /etc/nginx/conf.d/portainer.conf
```

`server { ... }` bloğunun **içine** ekleyin:

```nginx
location /uygulamaadi/ {
    proxy_pass http://host.docker.internal:XXXX/uygulamaadi/;
    proxy_http_version 1.1;
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_read_timeout 600s;
    proxy_send_timeout 600s;
}
```

Nginx reload:

```bash
sudo docker exec nginx-proxy nginx -s reload
```

---

### 9. Portainer'dan Stack Deploy

1. `https://javaarge.ibb.gov.tr:9443` adresine gidin
2. **Stacks** → **Add stack**
3. Stack adı: `uygulamaadi`
4. Web editor'e `docker-compose.yml` içeriğini yapıştırın
5. **Deploy the stack** butonuna tıklayın

> **Not:** Portainer'a `http://javaarge.ibb.gov.tr/portainer/` üzerinden girerseniz CSRF hatası alabilirsiniz. Direkt `https://javaarge.ibb.gov.tr:9443` kullanın.

---

### 10. JAVA_HOME Sorunu (Önemli!)

IBB'deki `eclipse-temurin` image'ları JRE ile gelir, `JAVA_HOME=/opt/java/openjdk` olarak set edilir. Uygulama içinden Maven çalıştırılıyorsa JDK gerekir.

**Çözüm:** Container'ı `docker run` ile başlatın ve `JAVA_HOME`'u override edin:

```bash
sudo docker stop uygulamaadi && sudo docker rm uygulamaadi

sudo docker run -d \
  --name uygulamaadi \
  --restart unless-stopped \
  -p XXXX:8080 \
  -e TZ=Europe/Istanbul \
  -e JAVA_HOME=/usr/lib/jvm/java-25-openjdk \
  uygulamaadi:latest
```

Kontrol:
```bash
sudo docker exec uygulamaadi env | grep JAVA
# JAVA_HOME=/usr/lib/jvm/java-25-openjdk olmalı
```

---

## Güncelleme (Yeni Versiyon Deploy)

```powershell
# 1. Lokalde derle
mvn package -DskipTests

# 2. JAR'ı sunucuya kopyala
scp target\uygulama-1.0.0.jar <kullanici>@javaarge.ibb.gov.tr:/opt/uygulamaadi/target/
```

```bash
# 3. Sunucuda yeniden build et
cd /opt/uygulamaadi
sudo docker build -t uygulamaadi:latest .

# 4. Container'ı yeniden başlat
sudo docker restart uygulamaadi
```

---

## Sık Kullanılan Komutlar

```bash
# Tüm container'ları listele
sudo docker ps

# Container loglarını canlı izle
sudo docker logs -f <container-name>

# Container içine gir
sudo docker exec -it <container-name> sh

# Container durdur/başlat/yeniden başlat
sudo docker stop <container-name>
sudo docker start <container-name>
sudo docker restart <container-name>

# Disk kullanımını kontrol et
df -h

# Docker temizliği (durdurulmuş container, kullanılmayan image ve cache)
sudo docker system prune -f

# Nginx config test
sudo docker exec nginx-proxy nginx -t

# Nginx reload
sudo docker exec nginx-proxy nginx -s reload
```

---

## Sık Karşılaşılan Hatalar

### 1. "No space left on device"
`/var` dolu (10G limit). Docker temizliği yapın:
```bash
sudo docker system prune -af
```

### 2. "Network unreachable" (Maven)
Sunucu internete çıkamıyor. IBB Nexus mirror kullanın (`/root/.m2/settings.xml`).

### 3. "PKIX path building failed" (SSL)
IBB'nin SSL sertifikası Java truststore'da yok. Dockerfile'daki sertifika import adımını ekleyin.

### 4. "No compiler is provided" (Maven)
`JAVA_HOME` JRE'ye işaret ediyor, JDK'ya değil. Container'ı `JAVA_HOME=/usr/lib/jvm/java-25-openjdk` env'i ile çalıştırın.

### 5. "Forbidden - origin invalid" (Portainer)
Portainer'a path üzerinden (`/portainer/`) değil, direkt port ile (`https://javaarge.ibb.gov.tr:9443`) girin.

### 6. Docker daemon çalışmıyor
```bash
sudo systemctl start docker
sudo systemctl status docker
```

---

## Dizin Yapısı

```
/opt/
├── soap-client-generator/       # WSDL Soap Client Generator
│   ├── Dockerfile
│   ├── docker-compose.yml
│   └── target/
│       └── wsdl-client-generator-1.0.0.jar
├── uygulamaadi/                 # Yeni uygulama buraya
│   ├── Dockerfile
│   ├── docker-compose.yml
│   └── target/
│       └── uygulama-1.0.0.jar
/etc/nginx/conf.d/
└── portainer.conf               # Tüm nginx route'ları burada
/data/docker/                    # Docker root dizini (100G)
```

---

## Port Listesi

| Port | Uygulama |
|------|----------|
| 80 | nginx-proxy (HTTP) |
| 587 | postfix |
| 4440 | rundeck |
| 5432 | keycloak-postgres |
| 5433 | rundeck-postgres |
| 6379 | redis |
| 8000 | portainer |
| 8001 | redis web UI |
| 8081 | ibb-user-maestro |
| 8085 | soap-client-generator |
| 9090 | keycloak |
| 9443 | portainer (HTTPS) |
| **8086+** | **Yeni uygulamalar için** |

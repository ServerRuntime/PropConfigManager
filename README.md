#Config Manager

Uzak Linux sunucularındaki `application.properties` dosyalarını SSH üzerinden tarayıcıdan yönetmeye yarayan Spring Boot tabanlı bir web uygulaması.

---

## İçindekiler

1. [Genel Bakış](#genel-bakış)
2. [Özellikler](#özellikler)
3. [Gereksinimler](#gereksinimler)
4. [Kurulum](#kurulum)
   - [Yerel Çalıştırma](#yerel-çalıştırma)
   - [Docker ile Çalıştırma](#docker-ile-çalıştırma)
5. [Yapılandırma](#yapılandırma)
   - [application.properties](#applicationproperties)
   - [machines.json](#machinesjson)
6. [Kullanım Kılavuzu](#kullanım-kılavuzu)
7. [API Referansı](#api-referansı)
8. [Teknik Mimari](#teknik-mimari)

---

## Genel Bakış

FlexCity Config Manager, 13 farklı sunucu ortamının (prod, test, dev, geçiş, vezne, vs.) yapılandırma dosyalarını merkezi olarak yönetmek için geliştirilmiştir. Sunucuya SSH ile bağlanıp terminal açmak yerine, şirket ağındaki herhangi bir bilgisayardan tarayıcı aracılığıyla tüm işlemler gerçekleştirilebilir.


---

## Özellikler

### 🔐 Güvenli Giriş
- Şifre korumalı giriş ekranı
- Session tabanlı oturum yönetimi
- Tüm API endpoint'leri auth interceptor ile korunuyor

### 📋 Properties Yönetimi
- Uzak sunucudaki `application.properties` dosyasını okuma ve listeleme
- Property ekleme, düzenleme, silme
- Her yazma işleminde sunucuda otomatik `.bak` yedeği oluşturma
- Yorum satırları ve boş satırlar korunarak dosya bütünlüğü sağlama
- Dışa aktarma (CSV / kopyalama)
- Property şablonları — sık kullanılan ayar gruplarını kaydet ve tek tıkla uygula

### ▶️ Servis Kontrolü
- FYS servisinin anlık durumunu görme (`active`, `inactive`, `failed`)
- Başlat / Durdur / Yeniden Başlat işlemleri
- `sudo systemctl` ile servis yönetimi

### 📟 Canlı Log İzleme
- `tail -f` ile gerçek zamanlı log akışı (SSE — Server-Sent Events)
- Renk kodlu çıktı: ERROR (kırmızı), WARN (sarı), INFO (mavi)
- Metin arama ve filtreler (ALL / ERROR / WARN / INFO)
- Otomatik kaydırma, satır limiti, log temizleme

### 🔍 Global Arama
- Bir anahtar veya değeri tüm makinelerde paralel olarak arama
- Eşleşen kısımlar vurgulanarak makine bazında listeleme

### 🔀 Makine Karşılaştırma (Diff)
- İki sunucunun property dosyalarını yan yana karşılaştırma
- Farklı değerler, sadece A'da olanlar, sadece B'de olanlar, aynı olanlar — renk kodlu
- Sürüklenebilir ve boyutlandırılabilir pencere

### 🎨 Arayüz
- Dark / Light tema desteği
- Sunucu arama ve filtreleme (sidebar)
- Yeniden boyutlandırılabilir diff ve arama pencereleri

---

## Gereksinimler

### Yerel Çalıştırma
- Java 17+
- Maven 3.8+

### Docker ile Çalıştırma
- Docker Engine
- Docker Compose v2

---

## Kurulum

### Yerel Çalıştırma

```bash
# 1. Projeyi klonla / klasöre gir
cd config-manager

# 2. Bağımlılıkları yükle ve JAR derle
mvn package -DskipTests

# 3. Çalıştır
java -jar target/config-manager-1.0.0.jar
```

Uygulama `http://localhost:5050` adresinde başlar.

---

### Docker ile Çalıştırma

#### İlk kurulum

```bash
# 1. JAR'ı yerel Maven ile derle (şirket ağı SSL denetimi nedeniyle Docker içinde derlenemiyor)
mvn package -DskipTests

# 2. Docker image oluştur ve container'ları başlat
docker compose up -d --build
```

#### Günlük kullanım komutları

```bash
# Başlat
docker compose up -d

# Durdur
docker compose down

# Yeniden başlat
docker compose restart

# Logları canlı izle
docker compose logs -f

# Durum kontrol
docker compose ps
```

#### Güncelleme (kod değişikliği sonrası)

```bash
mvn package -DskipTests
docker compose up -d --build
```

> **Not:** Sunucu yeniden başladığında container `restart: unless-stopped` ayarı sayesinde otomatik olarak ayağa kalkar.

---

## Yapılandırma

### application.properties

`src/main/resources/application.properties` dosyası veya Docker ortamında environment variable olarak override edilebilir.

| Parametre | Varsayılan | Açıklama |
|-----------|-----------|----------|
| `server.port` | `5050` | Uygulama portu |
| `app.ui.password` | `1234` | UI giriş şifresi |
| `app.machines-file` | `machines.json` | Makine listesi dosyası |
| `app.ssh.timeout` | `10000` | SSH bağlantı zaman aşımı (ms) |
| `app.remote.properties-path` | `******` | Uzak sunucudaki properties dosyası yolu |
| `logging.level.com.flexcity` | `INFO` | Log seviyesi (`DEBUG` sorun tespitinde kullanılır) |

**Docker ortamında şifre değiştirmek için** `docker-compose.yml` dosyasındaki ilgili satırı düzenle:
```yaml
environment:
  - APP_UI_PASSWORD=yeni_sifre
```
Sonra `docker compose up -d` komutunu çalıştır.

---

### machines.json

Sunucu listesi `machines.json` dosyasında tanımlanır. Docker kurulumunda bu dosya volume mount ile container dışında tutulur — container yeniden oluşturulsa bile veriler korunur.

```json
[
  {
    "id": "machine-01",
    "name": "****",
    "host": "***",
    "port": 22,
    "environment": "PROD",
    "description": "Prod sunucusu",
    "serviceName": "***",
    "sudoUser": "****",
    "logFile": "***",
    "username": "kullanici",
    "password": "sifre"
  }
]
```

| Alan | Açıklama |
|------|----------|
| `id` | Benzersiz makine kimliği |
| `name` | Arayüzde görünen ad |
| `host` | IP adresi |
| `port` | SSH portu (genellikle 22) |
| `environment` | Ortam etiketi (prod, test, dev...) |
| `serviceName` | `systemctl` servis adı |
| `sudoUser` | Properties dosyasının sahibi kullanıcı |
| `logFile` | İzlenecek log dosyasının tam yolu |
| `username` | SSH kullanıcı adı |
| `password` | SSH şifresi |

> ⚠️ `machines.json` dosyası şifre içerdiğinden `.gitignore`'a eklenmiştir. Git'e commit etmeyin.

---

## Kullanım Kılavuzu

### Sunucu Seçme
Sol panelden sunucuya tıkla. Properties otomatik yüklenir.

### Property Düzenleme
Tabloda bir satırın sağındaki kalem ikonuna tıkla, değeri güncelle, Kaydet'e bas.

### Yeni Property Ekleme
Sağ üstteki **Ekle** butonuna bas, key ve value gir.

### Servis Yönetimi
İçerik çubuğundaki **Başlat / Durdur / Restart** butonlarını kullan. İşlem sonrası durum otomatik güncellenir.

### Canlı Log İzleme
**Loglar** sekmesine geç → **Bağlan** butonuna bas. Log satırları gerçek zamanlı akmaya başlar. Bağlantıyı kesmek için **Bağlantıyı Kes** butonuna bas.

### Global Arama
Header'daki **Ara** butonuna bas → arama kutusuna key veya value gir → Enter. Tüm makineler paralel sorgulanır.

### Makine Karşılaştırma
Header'daki **Karşılaştır** butonuna bas → iki makine seç → **Karşılaştır**'a bas. Pencereyi başlıktan sürükleyebilir, sağ alt köşeden boyutlandırabilirsin.

---

## API Referansı

Tüm endpoint'ler `/api/` prefix'i altındadır ve oturum açılmasını gerektirir.

| Method | Endpoint | Açıklama |
|--------|----------|----------|
| `GET` | `/api/machines` | Makine listesi |
| `GET` | `/api/properties/{machineId}` | Properties oku |
| `POST` | `/api/properties/{machineId}/add` | Property ekle |
| `PUT` | `/api/properties/{machineId}` | Property güncelle |
| `DELETE` | `/api/properties/{machineId}` | Property sil |
| `GET` | `/api/service/{machineId}/status` | Servis durumu |
| `POST` | `/api/service/{machineId}/start` | Servisi başlat |
| `POST` | `/api/service/{machineId}/stop` | Servisi durdur |
| `POST` | `/api/service/{machineId}/restart` | Servisi yeniden başlat |
| `GET` | `/api/log/{machineId}` | Canlı log akışı (SSE) |
| `GET` | `/api/diff?machineA=...&machineB=...` | İki makine karşılaştır |
| `GET` | `/api/search?query=...` | Global arama |
| `GET` | `/auth/check` | Oturum kontrolü |
| `POST` | `/auth/login` | Giriş |
| `POST` | `/auth/logout` | Çıkış |

---

## Teknik Mimari

```
Tarayıcı
   │
   ▼
Nginx (port 30050)          ← Reverse proxy, SSE desteği
   │
   ▼
Spring Boot (port 5050)     ← REST API, Auth, SSE
   │
   ├── MachineService        ← machines.json okuma
   ├── SshService            ← JSch ile SSH/SFTP işlemleri
   │     ├── sudo -u <user>  ← Önce dene
   │     └── sudo (root)     ← Fallback (bazı makinelerde -u izni yok)
   └── AuthController        ← Session tabanlı kimlik doğrulama

Teknolojiler:
  - Java 17, Spring Boot 3.2.5
  - JSch (com.github.mwiede:jsch:0.2.17) — SSH/SFTP
  - Vanilla JS, Font Awesome — Frontend
  - Nginx Alpine — Reverse proxy
  - Docker + Docker Compose — Container altyapısı
```

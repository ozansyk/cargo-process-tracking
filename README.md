# Kargo Süreç Takip Projesi (Cargo Process Tracking)

Bu proje, Spring Boot, Camunda Platform 7, Thymeleaf ve PostgreSQL kullanılarak geliştirilmiş bir kargo süreç takip sistemidir. Kullanıcıların yeni kargo oluşturmasına, kargoları sorgulamasına, BPMN süreçlerini yükleyip yönetmesine ve kargo süreçlerini Camunda iş akışları üzerinden ilerletmesine olanak tanır.

## İçindekiler

1.  [Genel Bakış](#genel-bakış)
2.  [Teknolojiler](#teknolojiler)
3.  [Özellikler](#özellikler)
4.  [Kurulum ve Yerel Ortamda Çalıştırma](#kurulum-ve-yerel-ortamda-çalıştırma)
    *   [Gereksinimler](#gereksinimler)
    *   [Veritabanı Kurulumu (PostgreSQL)](#veritabanı-kurulumu-postgresql)
    *   [Proje Klonlama ve Yapılandırma](#proje-klonlama-ve-yapılandırma)
    *   [Uygulamayı Yerel Olarak Çalıştırma](#uygulamayı-yerel-olarak-çalıştırma)
5.  [Docker ile Çalıştırma](#docker-ile-çalıştırma)
    *   [Docker Gereksinimleri](#docker-gereksinimleri)
    *   [Dockerfile](#dockerfile)
    *   [Docker İmajını Oluşturma](#docker-imajını-oluşturma)
    *   [Docker Konteynerini Çalıştırma](#docker-konteynerini-çalıştırma)
        *   [Senaryo 1: PostgreSQL Ana Makinede](#senaryo-1-postgresql-ana-makinede)
        *   [Senaryo 2: PostgreSQL Docker Konteynerinde (Docker Compose ile Önerilir)](#senaryo-2-postgresql-docker-konteynerinde-docker-compose-ile-önerilir)
    *   [Docker İmaj ve Konteyner Yönetimi](#docker-imaj-ve-konteyner-yönetimi)
6.  [Kullanım](#kullanım)
    *   [Panel Arayüzü](#panel-arayüzü)
    *   [Camunda Web Arayüzleri](#camunda-web-arayüzleri)
    *   [API Endpoint'leri](#api-endpointleri)
7.  [Proje Yapısı](#proje-yapısı)
8.  [Camunda Süreç Modeli (`cargoTrackingProcessV3.bpmn`)](#camunda-süreç-modeli-cargotrackingprocessv3bpmn)
9.  [Geliştirme Notları](#geliştirme-notları)
10. [Katkıda Bulunma](#katkıda-bulunma)
11. [Lisans](#lisans)

## Genel Bakış

Bu uygulama, kargo şirketleri veya lojistik operasyonları için bir süreç takip ve yönetim aracı sunar. Temel kargo bilgilerinin kaydından başlayarak, kargonun çeşitli aşamalardan (alım, transfer, dağıtım, teslimat) geçişini Camunda BPMN motoru ile modellenmiş bir iş akışı üzerinden yönetir. Kullanıcılar, web tabanlı bir panel üzerinden bu süreçleri izleyebilir, yeni kargolar ekleyebilir ve mevcut görevleri tamamlayabilirler.

## Teknolojiler

*   **Backend:**
    *   Java 17
    *   Spring Boot 3.3.x (veya güncel stabil versiyonunuz)
    *   Spring Data JPA & Hibernate
    *   Spring Security 6
    *   Camunda Platform 7 BPMN Engine (Örn: 7.22.0)
    *   Lombok
*   **Frontend:**
    *   Thymeleaf & Thymeleaf Layout Dialect
    *   Bootstrap 5
    *   JavaScript (Vanilla JS, Fetch API)
*   **Veritabanı:**
    *   PostgreSQL (Örn: 14, 15)
*   **Build & Paketleme:**
    *   Apache Maven
    *   Docker
*   **Diğer:**
    *   SLF4J (Logback ile loglama)
    *   Spring Mail (E-posta gönderimi için)

## Özellikler

*   **Kullanıcı Paneli:**
    *   Kullanıcı adı/şifre ile güvenli giriş (Spring Security).
    *   Ana panelde kargo durumlarına göre özet istatistikler ve son işlemler.
    *   Yeni kargo kaydı oluşturma ve Camunda sürecini otomatik başlatma.
    *   Kargoları takip numarası, müşteri bilgisi veya durumuna göre filtreleyerek sorgulama.
    *   Kargo detaylarını (gönderici, alıcı, içerik, boyut, ağırlık, hareket geçmişi) görüntüleme.
    *   Panel üzerinden aktif Camunda kullanıcı görevlerini tamamlama.
        *   Paralel görevler için (örn: Fiziksel Alım, Fatura Oluşturma) ayrı işlem butonları.
        *   Fatura oluşturma gibi görevler için basit veri giriş formu.
    *   Kargo süreçlerini iptal etme.
    *   **Yönetici İşlevleri (ADMIN rolü için):**
        *   Yeni BPMN süreç tanımlarını yükleme (deploy etme).
        *   Deploy edilmiş süreçlerden yeni örnekler (instance) başlatma (iş anahtarı ve değişkenlerle).
        *   Sistemdeki tüm aktif kullanıcı görevlerini listeleme ve tamamlama (gerekli değişkenleri girerek).
        *   (Geliştirilebilir) Kullanıcı yönetimi.
*   **İş Süreci Yönetimi (Camunda BPMN):**
    *   `cargoTrackingProcessV3.bpmn` ile modellenmiş kapsamlı kargo takip süreci.
    *   Başlangıçta paralel ilerleyen "Fiziksel Alım Onayı" ve "Fatura Oluştur/Onayla" görevleri.
    *   Java Delegate'ler (`CargoStatusUpdateWorker`) aracılığıyla kargo durumlarının veritabanında otomatik güncellenmesi.
    *   Her ana adımdan sonra iptal veya devam kararı için exclusive gateway'ler.
    *   Süreç değişkenleri ile esnek akış kontrolü (`isCancelled`, `canProceedTo...`, fatura bilgileri vb.).
*   **API Katmanı:**
    *   Kargo oluşturma, iptal etme, adım tamamlama (belirli bir kargonun belirli bir görev anahtarı ile) ve kargo detaylarını sorgulama için RESTful API endpoint'leri.
    *   Genel görev tamamlama için (task ID ile) API endpoint'i.
*   **Bildirimler:**
    *   Kargo durumu güncellendiğinde alıcıya e-posta ile bilgilendirme.

## Kurulum ve Yerel Ortamda Çalıştırma

### Gereksinimler

*   **Java Development Kit (JDK):** Sürüm 17 veya üzeri. (`java --version`)
*   **Apache Maven:** Sürüm 3.6 veya üzeri. (`mvn --version`)
*   **PostgreSQL:** Sürüm 12 veya üzeri, çalışan bir sunucu.
*   **Git:** Projeyi klonlamak için. (`git --version`)
*   **IDE (Önerilen):** IntelliJ IDEA, Eclipse, VS Code (Java ve Maven/Spring eklentileriyle).

### Veritabanı Kurulumu (PostgreSQL)

1.  PostgreSQL sunucunuzu kurun ve çalışır durumda olduğundan emin olun.
2.  Uygulama için bir veritabanı ve kullanıcı oluşturun:
    ```sql
    -- psql konsolunda veya pgAdmin gibi bir araçla:
    CREATE DATABASE cargo_tracking_db;
    CREATE USER cargo_user WITH ENCRYPTED PASSWORD 'gucluBirSifre!'; -- Güçlü bir şifre belirleyin
    GRANT ALL PRIVILEGES ON DATABASE cargo_tracking_db TO cargo_user;
    ALTER DATABASE cargo_tracking_db OWNER TO cargo_user;
    -- Camunda'nın kendi şemasını oluşturabilmesi için (genellikle Spring Boot ile otomatik yapılır):
    -- Gerekirse, cargo_user'a schema oluşturma yetkisi verin veya veritabanını bu kullanıcıya ait yapın.
    ```

### Proje Klonlama ve Yapılandırma

1.  **Projeyi Klonlayın:**
    ```bash
    git clone <proje_git_url_adresiniz>
    cd cargo-process-tracking
    ```
    (`<proje_git_url_adresiniz>` kısmını kendi Git reponuzun adresi ile değiştirin.)

2.  **Yapılandırma Dosyası (`application.yaml`):**
    Projenin `src/main/resources/application.yaml` dosyasını kendi ortamınıza göre düzenleyin. Özellikle veritabanı bağlantı bilgileri, Camunda admin kullanıcısı ve e-posta ayarlarını kontrol edin.

    ```yaml
    server:
      port: 1992 # Uygulamanın çalışacağı port

    spring:
      application:
        name: cargo-process-tracking
      datasource:
        url: jdbc:postgresql://${POSTGRES_HOST:localhost}:${POSTGRES_PORT:5432}/${POSTGRES_DB_NAME:cargo_tracking_db}
        username: ${POSTGRES_USER:cargo_user}
        password: ${POSTGRES_PASSWORD:gucluBirSifre!} # Ortam değişkeni veya direkt şifre
        driver-class-name: org.postgresql.Driver
      jpa:
        hibernate:
          ddl-auto: update # Geliştirme için 'update', üretim için 'validate' veya 'none'
        show-sql: false # Geliştirme sırasında logları görmek için 'true' yapılabilir
      mail: # E-posta ayarları (gerçek değerlerle doldurun)
        host: ${MAIL_HOST:smtp.example.com}
        port: ${MAIL_PORT:587}
        username: ${MAIL_USERNAME:user@example.com}
        password: ${MAIL_PASSWORD:mail_sifresi}
        protocol: smtp
        properties.mail.smtp:
          auth: true
          starttls.enable: true
          # Gerekirse diğer SMTP özellikleri

    camunda:
      bpm:
        admin-user:
          id: ${CAMUNDA_ADMIN_USER:camunda-admin}
          password: ${CAMUNDA_ADMIN_PASSWORD:camunda-sifre} # Güçlü bir şifre belirleyin
          # firstName, lastName, email opsiyonel
        database:
          schema-update: true # Camunda şemasını otomatik oluşturur/günceller
          type: postgres
        # ... diğer Camunda ayarları ...
    ```
    **Önemli:** Hassas bilgileri (şifreler) doğrudan `application.yaml`'a yazmak yerine **ortam değişkenleri (environment variables)** ile yönetmek en iyi pratiktir. Yukarıdaki örnekte `${VARIABLE_NAME:default_value}` formatı kullanılmıştır.

    **Ortam Değişkenleri Örnekleri:**
    *   `POSTGRES_HOST`, `POSTGRES_PORT`, `POSTGRES_DB_NAME`, `POSTGRES_USER`, `POSTGRES_PASSWORD`
    *   `CAMUNDA_ADMIN_USER`, `CAMUNDA_ADMIN_PASSWORD`
    *   `MAIL_HOST`, `MAIL_PORT`, `MAIL_USERNAME`, `MAIL_PASSWORD`

### Uygulamayı Yerel Olarak Çalıştırma

1.  **Maven ile Derleme (Opsiyonel, `spring-boot:run` genellikle derlemeyi de yapar):**
    ```bash
    mvn clean package -DskipTests
    ```

2.  **Uygulamayı Çalıştırma:**
    *   **Maven ile:**
        ```bash
        # Ortam değişkenlerini set ettikten sonra (eğer kullanıyorsanız)
        # Örn: export POSTGRES_PASSWORD="gucluBirSifre!" (Linux/macOS)
        #      set POSTGRES_PASSWORD="gucluBirSifre!" (Windows Cmd)
        #      $env:POSTGRES_PASSWORD="gucluBirSifre!" (Windows PowerShell)
        mvn spring-boot:run
        ```
    *   **Oluşturulan JAR Dosyası ile:**
        ```bash
        java -jar target/cargo-process-tracking-*.jar
        ```
        (Yine ortam değişkenlerinin set edilmiş olması gerekir.)

3.  Uygulama başarıyla başladığında erişim adresleri:
    *   **Panel Arayüzü:** `http://localhost:1992/panel`
    *   **Camunda Web Arayüzleri:** `http://localhost:1992/camunda/app/`
        *   **Varsayılan Camunda Admin Girişi:** `camunda-admin` / `camunda-sifre` (veya `application.yaml`'da belirlediğiniz)
        *   **Varsayılan Spring Security Kullanıcısı (Test için):** `admin` / `password` (`application.yaml`'da tanımlıysa veya `SecurityConfig`'de)

## Docker ile Çalıştırma

### Docker Gereksinimleri

*   **Docker Desktop** (Windows/macOS) veya **Docker Engine** (Linux) kurulu ve çalışır durumda olmalıdır.

### Dockerfile

Proje kök dizininde aşağıdaki gibi bir `Dockerfile` bulunmaktadır:

```dockerfile
FROM maven:3.9-eclipse-temurin-17-alpine AS build
WORKDIR /app
COPY . .
RUN mvn clean package -DskipTests

FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
EXPOSE 1992
ENTRYPOINT ["java","-jar","app.jar"]
```

Docker İmajını Oluşturma
Proje kök dizininde terminali açın ve aşağıdaki komutu çalıştırın:
```bash
docker build -t cargo-app:latest .
```
(cargo-app:latest yerine istediğiniz bir imaj adı ve etiketi verebilirsiniz.)

### Docker Konteynerini Çalıştırma

#### Senaryo 1: PostgreSQL Ana Makinede (veya Uzak Sunucuda)

Eğer PostgreSQL Docker dışında bir yerde çalışıyorsa, konteynerin bu veritabanına erişebilmesi gerekir.
*   **Windows/macOS (Docker Desktop):** Ana makineye erişim için `host.docker.internal` adresini kullanabilirsiniz.
*   **Linux:** Ana makine IP adresini veya Docker host network'ünü (`--network="host"`) kullanabilirsiniz (host network'ü port çakışmalarına neden olabilir, dikkatli olun).

Ortam değişkenlerini içeren bir `.env` dosyası (örn: `docker.env`) oluşturun:
```env
# docker.env dosyası örneği
POSTGRES_HOST=host.docker.internal # Veya PostgreSQL sunucunuzun IP/hostname'i
POSTGRES_PORT=5432
POSTGRES_DB_NAME=cargo_tracking_db
POSTGRES_USER=cargo_user
POSTGRES_PASSWORD=gucluBirSifre!
CAMUNDA_ADMIN_USER=camunda-admin
CAMUNDA_ADMIN_PASSWORD=camunda-sifre
# Diğer gerekli ortam değişkenleri...
```

UYARI: .env dosyasını .gitignore'a ekleyerek Git'e göndermeyin.
Konteyneri çalıştırın (logları görmek için -d olmadan):
```bash
docker run --rm -p 1992:1992 --env-file ./docker.env --name cargo-container cargo-app:latest
```
(--rm konteyner durduğunda kaldırır, kalıcı olmasını istiyorsanız kaldırın.)
Senaryo 2: PostgreSQL Docker Konteynerinde (Docker Compose ile Önerilir)
Bu senaryo için proje kök dizininde bir docker-compose.yml dosyası oluşturun:
```bash
version: '3.8'

services:
  postgres_db:
    image: postgres:15-alpine
    container_name: cargo_postgres_db
    restart: unless-stopped
    environment:
      POSTGRES_DB: cargo_tracking_db
      POSTGRES_USER: cargo_user
      POSTGRES_PASSWORD: yourSuperStrongPassword! # GÜVENLİ BİR ŞİFRE KULLANIN
    ports:
      - "5433:5432" # Ana makine portu 5433, konteyner portu 5432
    volumes:
      - cargo_postgres_data:/var/lib/postgresql/data
    networks:
      - cargo_network

  cargo_app:
    build:
      context: .
      dockerfile: Dockerfile # Dockerfile adınız farklıysa belirtin
    container_name: cargo_app_service
    restart: unless-stopped
    depends_on:
      - postgres_db
    ports:
      - "1992:1992"
    environment:
      # Spring Boot datasource ayarları (PostgreSQL servis adına işaret eder)
      SPRING_DATASOURCE_URL: jdbc:postgresql://postgres_db:5432/cargo_tracking_db
      SPRING_DATASOURCE_USERNAME: cargo_user
      SPRING_DATASOURCE_PASSWORD: yourSuperStrongPassword! # Yukarıdakiyle aynı olmalı
      # Camunda Admin Kullanıcısı
      CAMUNDA_ADMIN_USER: camunda-admin
      CAMUNDA_ADMIN_PASSWORD: yourCamundaAdminPassword! # GÜVENLİ BİR ŞİFRE KULLANIN
      # Diğer ortam değişkenleri (mail vb.)
      # SPRING_JPA_HIBERNATE_DDL_AUTO: update # Gerekirse
    networks:
      - cargo_network

volumes:
  cargo_postgres_data:
    driver: local

networks:
  cargo_network:
    driver: bridge
```
docker-compose.yml dosyasındaki şifreleri güncelleyin. Ardından çalıştırın:
```bash
docker-compose up --build
```
Arkaplanda çalıştırmak için:``` docker-compose up -d --build```
Durdurmak için: ``` docker-compose down```
Docker İmaj ve Konteyner Yönetimi
Çalışan Konteynerleri Listele: ```docker ps```
Tüm Konteynerleri Listele: ```docker ps -a```
Konteyneri Durdur: ```docker stop <container_adı_veya_id>```
Konteyneri Kaldır: ```docker rm <container_adı_veya_id> (Önce durdurulmalı)```
Tüm Durmuş Konteynerleri Kaldır: ```docker container prune```
İmajları Listele: ```docker images```
İmajı Kaldır: ```docker rmi <imaj_adı:etiket_veya_id>``` (Bu imajı kullanan konteyner olmamalı)
Kullanılmayan (Dangling) İmajları Kaldır: ```docker image prune```
Konteyner Loglarını Göster: ```docker logs <container_adı_veya_id> -f```
Kullanım
Panel Arayüzü
Giriş: ```/login```
Ana Panel: ```/panel```
Yeni Kargo: ```/panel/yeni-kargo```
Kargo Sorgula: ```/panel/sorgula```
Aktif Görevlerim (Admin): ```/panel/aktif-gorevler```
BPMN Yükle (Admin): ```/panel/deployments/new-bpmn```
Süreç Başlat (Admin): ```/panel/deployments/start-instance```
Kullanıcı Yönetimi (Admin): ```/panel/kullanici-yonetimi```
Camunda Web Arayüzleri
Genel Giriş: ```http://localhost:1992/camunda/app/```
Cockpit: Süreçleri izleme ve yönetme.
Tasklist: Manuel kullanıcı görevlerini tamamlama.
Admin: Kullanıcı, grup ve yetki yönetimi.
API Endpoint'leri
Ana API yolu: ```/api/cargos```
POST /: Yeni kargo oluşturur.
PUT /{trackingNumber}/cancel: Kargoyu iptal eder.
PUT /{trackingNumber}/complete-step/{taskDefinitionKey}: Kargonun belirli bir görev adımını tamamlar (gerekirse değişkenlerle).
GET /details/{trackingNumber}: Kargo detaylarını getirir.
PUT /tasks/{taskId}/complete: ID'si verilen herhangi bir aktif görevi (gerekirse değişkenlerle) tamamlar.
Proje Yapısı
com.ozansoyak.cargo_process_tracking
camundaworker: Camunda Java Delegate'leri.
config: Güvenlik, mail vb. konfigürasyonlar.
controller: Web ve API controller'ları.
dto: Veri Transfer Nesneleri.
exception: Özel hatalar.
model: JPA entity'leri, enum'lar.
repository: Veritabanı arayüzleri.
service: İş mantığı katmanı.
src/main/resources
application.yaml: Uygulama ayarları.
bpmn/: BPMN süreç dosyaları (örn: cargoTrackingProcessV3.bpmn).
static/: (Varsa) CSS, JS, resimler.
templates/: Thymeleaf HTML şablonları.
layout/main-layout.html: Ortak sayfa yapısı.
panel/: Panel sayfaları.
deploy-form.html, start-instance-form.html vb.
Camunda Süreç Modeli (cargoTrackingProcessV3.bpmn)
Proje, cargoTrackingProcessV3.bpmn adlı bir Camunda BPMN 2.0 süreç modelini kullanır.
Ana Adımlar:
Süreç Başlangıcı (Yeni Kargo Kaydı)
Paralel Görevler: Fiziksel Alım Onayı (userTask_PhysicalReception) & Fatura Oluştur/Onayla (userTask_InvoiceCreation)
Durum Güncelleme (task_UpdateStatusReceived) -> RECEIVED
Kullanıcı Onayı (userTask_ConfirmReceived) -> canProceedToLoaded1
Sonraki Aşamalar (Durum Güncelleme -> Kullanıcı Onayı -> Gateway şeklinde tekrarlar):
LOADED_ON_VEHICLE_1
AT_TRANSFER_CENTER
LOADED_ON_VEHICLE_2
AT_DISTRIBUTION_HUB
OUT_FOR_DELIVERY
DELIVERED
İptal Durumu (task_UpdateStatusCancelled) -> CANCELLED
Süreç Sonu
Önemli Süreç Değişkenleri:
cargoId, trackingNumber, isCancelled, canProceedTo..., invoiceGenerated, invoiceNumber, invoiceAmount
Geliştirme Notları
Thymeleaf Layout Dialect: Panel sayfalarında ortak UI bileşenleri için kullanılır.
Lombok: Tekrarlayan Java kodunu azaltır.
Hata Yönetimi: Global exception handler ve API'lerde uygun HTTP yanıtları.
Loglama: SLF4J (Logback ile). Log seviyeleri application.yaml'dan ayarlanabilir.
Veritabanı Şeması: spring.jpa.hibernate.ddl-auto: update ve camunda.bpm.database.schema-update: true ile yönetilir.
Asenkron İşlemler: E-posta gönderimi gibi işlemler @Async ile asenkron yapılabilir.
Katkıda Bulunma
Katkıda bulunmak isterseniz, lütfen standart GitHub fork & pull request akışını izleyin.
Projeyi fork edin.
Yeni bir branch oluşturun (git checkout -b ozellik/yeni-super-ozellik).
Değişikliklerinizi commit edin (git commit -am 'Yeni süper özellik eklendi').
Branch'inizi push edin (git push origin ozellik/yeni-super-ozellik).
Bir Pull Request açın.
Lisans
Bu proje MIT Lisansı altında lisanslanmıştır. (Eğer projenizde bir LICENSE.MD dosyası varsa.)
<!-- Veya: Bu proje için henüz bir lisans belirlenmemiştir. -->
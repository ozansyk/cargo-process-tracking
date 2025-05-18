# Kargo Süreç Takip Projesi (Cargo Process Tracking)

Bu proje, Spring Boot, Camunda Platform 7, Thymeleaf ve PostgreSQL kullanılarak geliştirilmiş bir kargo süreç takip sistemidir. Kullanıcıların yeni kargo oluşturmasına, kargoları sorgulamasına ve kargo süreçlerini Camunda iş akışları üzerinden yönetmesine olanak tanır.

## İçindekiler

1.  [Teknolojiler](#teknolojiler)
2.  [Özellikler](#özellikler)
3.  [Kurulum ve Çalıştırma](#kurulum-ve-çalıştırma)
    *   [Gereksinimler](#gereksinimler)
    *   [Veritabanı Kurulumu (PostgreSQL)](#veritabanı-kurulumu-postgresql)
    *   [Proje Klonlama ve Yapılandırma](#proje-klonlama-ve-yapılandırma)
    *   [Uygulamayı Çalıştırma](#uygulamayı-çalıştırma)
4.  [Kullanım](#kullanım)
    *   [Panel Arayüzü](#panel-arayüzü)
    *   [Camunda Arayüzleri](#camunda-arayüzleri)
    *   [API Endpoint'leri](#api-endpointleri)
5.  [Proje Yapısı](#proje-yapısı)
6.  [Camunda Süreç Modeli](#camunda-süreç-modeli)
7.  [Geliştirme Notları](#geliştirme-notları)

## Teknolojiler

*   **Backend:**
    *   Java 17
    *   Spring Boot 3.3.x
    *   Spring Data JPA
    *   Spring Security 6
    *   Camunda Platform 7 BPMN Engine (7.22.0)
    *   Lombok
*   **Frontend:**
    *   Thymeleaf
    *   Bootstrap 5
    *   JavaScript (Vanilla JS, Fetch API)
    *   Thymeleaf Layout Dialect
*   **Veritabanı:**
    *   PostgreSQL
*   **Build Aracı:**
    *   Apache Maven
*   **Diğer:**
    *   SLF4J (Loglama)

## Özellikler

*   **Kullanıcı Paneli:**
    *   Güvenli giriş (Spring Security).
    *   Ana panelde kargo durumlarına göre özet istatistikler.
    *   Son kargo hareketlerinin listelenmesi.
    *   Yeni kargo oluşturma formu.
    *   Kargo sorgulama ve filtreleme (takip no, müşteri bilgisi, durum).
    *   Kargo detaylarını görüntüleme (gönderici, alıcı, kargo bilgileri, hareket geçmişi).
    *   Aktif kargo süreç adımlarını tamamlama (Camunda görevlerini UI üzerinden ilerletme).
    *   Kargo süreçlerini iptal etme.
    *   (Opsiyonel) Kullanıcı yönetimi (ADMIN rolü için).
*   **İş Süreci Yönetimi (Camunda):**
    *   Detaylı kargo takip süreci (`cargoTrackingProcessV3.bpmn`).
    *   Paralel görevler (Fiziksel Alım ve Fatura Oluşturma).
    *   Otomatik durum güncellemeleri (Java Delegate'ler aracılığıyla).
    *   Süreç iptal mekanizması.
*   **API:**
    *   Kargo oluşturma, iptal etme, adım tamamlama ve detay sorgulama için RESTful API endpoint'leri.

## Kurulum ve Çalıştırma

### Gereksinimler

*   **Java Development Kit (JDK):** Sürüm 17 veya üzeri.
*   **Apache Maven:** Sürüm 3.6 veya üzeri.
*   **PostgreSQL:** Sürüm 12 veya üzeri.
*   **IDE (Önerilen):** IntelliJ IDEA, Eclipse, VS Code (Java eklentileriyle).
*   **Git:** Projeyi klonlamak için.

### Veritabanı Kurulumu (PostgreSQL)

1.  PostgreSQL sunucunuzu kurun ve çalışır durumda olduğundan emin olun.
2.  Uygulama için bir veritabanı oluşturun. Örneğin: `cargo_tracking_db`
3.  Uygulamanın bu veritabanına erişebileceği bir kullanıcı oluşturun ve gerekli yetkileri verin. Örneğin: `cargo_user` şifresiyle.

    ```sql
    CREATE DATABASE cargo_tracking_db;
    CREATE USER cargo_user WITH PASSWORD 'sifreniz';
    GRANT ALL PRIVILEGES ON DATABASE cargo_tracking_db TO cargo_user;
    -- Camunda'nın şema oluşturabilmesi için kullanıcının şema oluşturma yetkisine de ihtiyacı olabilir.
    -- Gerekirse: ALTER USER cargo_user CREATEDB; veya veritabanı sahibi olarak ayarlayın.
    ```

### Proje Klonlama ve Yapılandırma

1.  **Projeyi Klonlayın:**
    ```bash
    git clone <proje_git_url_adresiniz>
    cd cargo-process-tracking
    ```

2.  **Yapılandırma (`application.yaml`):**
    Projenin `src/main/resources/application.yaml` (veya `application.properties`) dosyasını kendi ortamınıza göre düzenleyin. Özellikle veritabanı bağlantı bilgilerini ve (kullanılıyorsa) mail sunucusu ayarlarını kontrol edin.

    ```yaml
    spring:
      datasource:
        url: jdbc:postgresql://${POSTGRES_HOST:localhost}:${POSTGRES_PORT:5432}/${POSTGRES_DB_NAME:cargo_tracking_db}
        username: ${POSTGRES_USER:cargo_user}
        password: ${POSTGRES_PASSWORD:sifreniz}
        driver-class-name: org.postgresql.Driver
      # ... diğer ayarlar ...
    camunda:
      bpm:
        admin-user:
          id: ${CAMUNDA_ADMIN_USER:camunda-admin} # Camunda admin kullanıcısı
          password: ${CAMUNDA_ADMIN_PASSWORD:camunda-password} # Camunda admin şifresi
    # ...
    ```
    Yukarıdaki `${...}` ile başlayan değerler ortam değişkenleridir. Eğer ortam değişkeni tanımlı değilse `:` sonrasındaki varsayılan değer kullanılır.

    **Ortam Değişkenleri (Environment Variables):**
    Hassas bilgileri (veritabanı şifresi, mail şifresi vb.) doğrudan `application.yaml` dosyasına yazmak yerine ortam değişkenleri ile yönetmek daha güvenlidir. Uygulamayı çalıştırırken bu değişkenleri ayarlayabilirsiniz:

    *   `POSTGRES_HOST`
    *   `POSTGRES_PORT`
    *   `POSTGRES_DB_NAME`
    *   `POSTGRES_USER`
    *   `POSTGRES_PASSWORD`
    *   `MAIL_HOST` (kullanılıyorsa)
    *   `MAIL_PORT` (kullanılıyorsa)
    *   `MAIL_USERNAME` (kullanılıyorsa)
    *   `MAIL_PASSWORD` (kullanılıyorsa)
    *   `CAMUNDA_ADMIN_USER` (Camunda admin kullanıcı adı)
    *   `CAMUNDA_ADMIN_PASSWORD` (Camunda admin şifresi)

    IDE'nizde "Run Configuration" ayarlarından veya işletim sisteminizin ortam değişkenleri bölümünden bu değerleri ayarlayabilirsiniz.

### Uygulamayı Çalıştırma

1.  **Maven ile Derleme:**
    Proje kök dizininde terminali açın ve aşağıdaki komutu çalıştırın:
    ```bash
    mvn clean install
    ```
    Bu komut, bağımlılıkları indirir, projeyi derler ve çalıştırılabilir bir JAR dosyası oluşturur (`target/cargo-process-tracking-0.0.1-SNAPSHOT.jar`).

2.  **Uygulamayı Çalıştırma:**
    *   **Maven ile (Geliştirme Ortamı):**
        ```bash
        mvn spring-boot:run
        ```
    *   **Oluşturulan JAR Dosyası ile:**
        ```bash
        java -jar target/cargo-process-tracking-0.0.1-SNAPSHOT.jar
        ```
        Eğer ortam değişkenlerini kullanıyorsanız, bu komutu çalıştırmadan önce ortam değişkenlerinin set edildiğinden emin olun. Örneğin:
        ```bash
        export POSTGRES_PASSWORD=sifreniz
        java -jar target/cargo-process-tracking-0.0.1-SNAPSHOT.jar
        ```

3.  Uygulama başarıyla başladığında, genellikle aşağıdaki adreslerden erişilebilir olacaktır:
    *   **Panel Arayüzü:** `http://localhost:1992/panel` (Port numarası `application.yaml` dosyasındaki `server.port` ayarına göre değişebilir)
    *   **Camunda Web Uygulamaları (Cockpit, Tasklist, Admin):** `http://localhost:1992/camunda/app/`
        *   Giriş için `application.yaml` dosyasında tanımlanan `camunda.bpm.admin-user` bilgileri (varsayılan: `camunda-admin` / `camunda-password`) veya kendi oluşturduğunuz kullanıcıları kullanabilirsiniz.

## Kullanım

### Panel Arayüzü

*   **Giriş:** Spring Security tarafından korunan `/login` sayfası üzerinden giriş yapın. `application.yaml` dosyasındaki `spring.security.user` (varsayılan: `admin`/`password`) veya veritabanınızdaki kullanıcılarla giriş yapabilirsiniz.
*   **Ana Panel (`/panel`):** Kargo durumlarına göre genel istatistikleri ve son işlemleri gösterir.
*   **Yeni Kargo Kaydı (`/panel/yeni-kargo`):** Gönderici, alıcı ve kargo detaylarını girerek yeni bir kargo süreci başlatmanızı sağlar. Başarılı kayıt sonrası bir takip numarası üretilir.
*   **Kargo Sorgula (`/panel/sorgula`):** Takip numarası, müşteri bilgisi veya kargo durumuna göre kargoları aramanızı sağlar. Sonuçlar sayfalama ile listelenir.
    *   **Detay/Güncelleme Modalı:** Listeden bir kargoya tıklandığında, kargonun genel bilgileri, hareket geçmişi ve o anki aktif süreç adımları için işlem butonları (örn: Fiziksel Alımı Onayla, Faturayı Oluştur, Sonraki Adımı Tamamla, Kargoyu İptal Et) içeren bir modal açılır.
        *   "Faturayı Oluştur/Onayla" gibi bazı görevler, ek bilgi girişi için modal içinde ayrı bir form açabilir.
*   **Kullanıcı Yönetimi (`/panel/kullanici-yonetimi`):** Sadece `ADMIN` rolüne sahip kullanıcılar erişebilir. (Bu özellik sizin tarafınızdan geliştirildiyse detayları buraya ekleyebilirsiniz.)

### Camunda Arayüzleri

*   **Camunda Cockpit (`/camunda/app/cockpit/`):**
    *   Aktif ve tamamlanmış süreç instanslarını (kargo süreçlerini) görüntüleyebilir, süreç değişkenlerini inceleyebilir ve süreç akışını takip edebilirsiniz.
    *   BPMN modelini canlı olarak görebilir, hangi adımda kaç tane instans olduğunu izleyebilirsiniz.
    *   Olası hataları (incident'ları) yönetebilirsiniz.
*   **Camunda Tasklist (`/camunda/app/tasklist/`):**
    *   Kullanıcılara atanmış veya belirli gruplara atanmış manuel görevleri (User Task) listeler. Panel arayüzü bu görevleri tamamlamak için bir alternatif sunar, ancak Tasklist üzerinden de görevler tamamlanabilir.
    *   Örneğin, `kargo-calisanlari` veya `muhasebe-ekibi` gruplarına ait görevler burada görünecektir.
*   **Camunda Admin (`/camunda/app/admin/`):**
    *   Camunda kullanıcılarını, gruplarını ve yetkilerini yönetebilirsiniz.

### API Endpoint'leri

Temel API endpoint'leri `CargoController.java` altında `/api/cargos` base path'i ile tanımlanmıştır:

*   `POST /api/cargos`: Yeni kargo oluşturur ve Camunda sürecini başlatır.
    *   Body: `CreateCargoRequest` JSON
    *   Dönüş: `CargoResponse` JSON
*   `PUT /api/cargos/{trackingNumber}/cancel`: Belirtilen takip numarasına sahip kargo sürecini iptal eder.
    *   Dönüş: Başarı/hata mesajı.
*   `PUT /api/cargos/{trackingNumber}/complete-step/{taskDefinitionKey}`: Belirtilen takip numarasına sahip kargonun, verilen `taskDefinitionKey` ile eşleşen aktif Camunda kullanıcı görevini tamamlar.
    *   Body (Opsiyonel): Görevle ilgili ek değişkenler (örn: fatura bilgileri için `{"invoiceNumber": "INV123", "invoiceAmount": 150.75}`).
    *   Dönüş: `TaskCompletionResponse` JSON (içinde başarı mesajı ve tamamlanan görevin adı/key'i bulunur).
*   `GET /api/cargos/details/{trackingNumber}`: Belirtilen takip numarasına sahip kargonun detaylı bilgilerini (hareket geçmişi, aktif görevler dahil) döndürür.
    *   Dönüş: `TrackingInfoResponse` JSON.

## Proje Yapısı

Projenin ana paket yapısı şu şekildedir:

*   `com.ozansoyak.cargo_process_tracking`
    *   `camundaworker`: Camunda Java Delegate sınıfları (örn: `CargoStatusUpdateWorker`).
    *   `config`: Spring Boot konfigürasyon sınıfları (örn: `SecurityConfig`).
    *   `controller`: Spring MVC Controller'ları (API için `CargoController`, Panel için `PanelController`).
    *   `dto`: Veri Transfer Nesneleri (Data Transfer Objects).
    *   `exception`: Özel exception sınıfları.
    *   `model`: JPA Entity sınıfları ve Enum'lar.
    *   `repository`: Spring Data JPA Repository arayüzleri.
    *   `service`: İş mantığının bulunduğu servis katmanı (arayüz ve implementasyonlar).
*   `src/main/resources`
    *   `application.yaml`: Ana uygulama yapılandırma dosyası.
    *   `bpmn`: Camunda BPMN süreç modelleri (`.bpmn` dosyaları). (Eğer süreç modellerini bu klasörde tutuyorsanız)
    *   `static`: Statik kaynaklar (CSS, JS, resimler - eğer kullanılıyorsa).
    *   `templates`: Thymeleaf şablonları (`.html` dosyaları).
        *   `layout`: Ortak layout şablonları.
    *   `messages.properties`: (Varsa) I18N mesajları.

## Camunda Süreç Modeli

Proje, `cargoTrackingProcessV3.bpmn` adlı bir Camunda BPMN 2.0 süreç modelini kullanır. Bu model, bir kargonun oluşturulmasından teslim edilmesine (veya iptal edilmesine) kadar olan tüm adımları tanımlar.

**Ana Adımlar:**

1.  **Süreç Başlangıcı:** Yeni kargo kaydı ile tetiklenir.
2.  **Paralel Görevler (Parallel Gateway):**
    *   `userTask_PhysicalReception`: Kargonun fiziksel olarak alındığının onaylanması (grup: `kargo-calisanlari`).
    *   `userTask_InvoiceCreation`: Kargo için faturanın oluşturulup onaylanması (grup: `muhasebe-ekibi`). Bu adım, UI'da ek bilgi girişi gerektirebilir.
3.  Her iki paralel görev tamamlandıktan sonra süreç devam eder.
4.  **Durum Güncelleme (Service Task - `task_UpdateStatusReceived`):** Kargo durumu "Alındı" olarak güncellenir (`#{cargoStatusUpdater}` delegate'i ile).
5.  **Kullanıcı Onayı (User Task - `userTask_ConfirmReceived`):** Alındı durumu onaylanır ve bir sonraki adıma geçiş için süreç değişkeni (`canProceedToLoaded1`) set edilir.
6.  **İptal/İlerleme Kontrolü (Exclusive Gateway):** `isCancelled` ve `canProceedTo...` değişkenlerine göre süreç ya iptal yoluna ya da bir sonraki normal adıma yönlenir.
7.  Bu yapı (Durum Güncelleme Service Task -> Kullanıcı Onay User Task -> İptal/İlerleme Gateway) kargo sürecinin her ana aşaması için tekrarlanır:
    *   İlk Araca Yüklendi (`LOADED_ON_VEHICLE_1`)
    *   Transfer Merkezinde (`AT_TRANSFER_CENTER`)
    *   Son Araca Yüklendi (`LOADED_ON_VEHICLE_2`)
    *   Dağıtım Bölgesinde (`AT_DISTRIBUTION_HUB`)
    *   Dağıtımda (`OUT_FOR_DELIVERY`)
    *   Teslim Edildi (`DELIVERED`)
8.  **İptal Durumu (Service Task - `task_UpdateStatusCancelled`):** Herhangi bir aşamada iptal kararı verilirse, kargo durumu "İptal Edildi" olarak güncellenir.
9.  **Süreç Sonu (End Event):** Kargo teslim edildiğinde veya iptal edildiğinde süreç sonlanır.

**Süreç Değişkenleri (Önemliler):**

*   `cargoId`: Veritabanındaki kargo entity'sinin ID'si.
*   `trackingNumber`: Kargonun iş anahtarı (business key) olarak da kullanılır.
*   `isCancelled`: Boolean, kargonun iptal edilip edilmediğini belirtir.
*   `canProceedTo...`: (örn: `canProceedToLoaded1`) Boolean, bir User Task tamamlandığında bir sonraki adıma geçiş iznini belirtir.
*   `invoiceGenerated`, `invoiceNumber`, `invoiceAmount`: Fatura oluşturma göreviyle ilgili değişkenler.

## Geliştirme Notları

*   **Thymeleaf Layout Dialect:** Panel sayfalarında ortak başlık, menü ve altbilgi için kullanılır (`layout/main-layout.html`).
*   **Lombok:** Boilerplate kodu azaltmak için (Getter, Setter, Constructor vb.) kullanılır.
*   **Hata Yönetimi:** API ve Servis katmanlarında standart Java exception'ları ve uygun HTTP durum kodları kullanılır. Camunda delegate'lerinde `BpmnError` fırlatılır.
*   **Loglama:** SLF4J (Logback ile) kullanılır. `application.yaml` dosyasından log seviyeleri ayarlanabilir.
*   **Veritabanı Şeması:** Spring Data JPA'nın `ddl-auto: update` (veya `validate`) özelliği ile yönetilir. Camunda da kendi tablolarını otomatik olarak oluşturur/günceller (`camunda.bpm.database.schema-update: true`).

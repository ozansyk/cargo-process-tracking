# ----- Build Stage -----
# Java 17 JDK içeren bir base image kullan (Alpine Linux daha küçüktür)
FROM eclipse-temurin:17-jdk-alpine AS builder

# Uygulama için çalışma dizini oluştur
WORKDIR /app

# Bağımlılıkları indirmek için önce pom.xml'i kopyala (Docker katman önbelleğinden yararlanmak için)
COPY pom.xml .

# Maven bağımlılıklarını indir (Sadece bağımlılıklar değiştiğinde bu katman yeniden çalışır)
# -B: Batch mode (etkileşimsiz)
RUN mvn dependency:go-offline -B

# Proje kaynak kodunu kopyala
COPY src ./src

# Uygulamayı paketle (Testleri atla - CI/CD pipeline'ında çalıştırılmalı)
# JAR dosyasının adını pom.xml'den al: cargo-process-tracking-0.0.1-SNAPSHOT.jar
# Spring Boot plugin'i çalıştırılabilir bir JAR oluşturacak
RUN mvn package -B -DskipTests

# ----- Runtime Stage -----
# Sadece Java 17 Runtime içeren daha küçük bir base image kullan
FROM eclipse-temurin:17-jre-alpine

# Güvenlik için non-root kullanıcı oluştur
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
USER appuser

# Uygulama için çalışma dizini oluştur
WORKDIR /app

# Build aşamasından oluşturulan JAR dosyasını kopyala ve yeniden adlandır
# pom.xml'deki artifactId ve version'a göre JAR dosyasının adını belirtiyoruz.
# Wildcard (*) kullanmak yerine tam ismi belirtmek daha güvenilir olabilir,
# ama SNAPSHOT versiyonlarda isim değişmeyeceği için wildcard da iş görür.
ARG JAR_FILE_PATH=/app/target/cargo-process-tracking-*.jar
COPY --from=builder ${JAR_FILE_PATH} app.jar

# Uygulamanın çalışacağı portu belirt (application.yml'den: 1992)
EXPOSE 1992

# Çalışma zamanında JVM seçeneklerini (örn: bellek ayarları) geçirebilmek için ENV
ENV JAVA_OPTS=""

# Uygulamayı başlatma komutu
# exec formunu kullanmak sinyallerin (örn. SIGTERM) doğru iletilmesini sağlar
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

# Opsiyonel: Temel bir sağlık kontrolü ekle
# Uygulamanın portuna TCP bağlantısı kurmayı dener
HEALTHCHECK --interval=30s --timeout=5s --start-period=15s \
  CMD nc -z localhost 1992 || exit 1
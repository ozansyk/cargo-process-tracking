# ----- Build Stage -----
# pom.xml'deki Java sürümü 17 olduğu için Java 17 içeren bir Maven imajı kullanıyoruz.
# Alpine tabanlı imajlar genellikle daha küçüktür.
FROM maven:3.9-eclipse-temurin-17-alpine AS build

# Build context'i için bir çalışma dizini ayarlamak iyi bir pratiktir.
WORKDIR /app

# Proje dosyalarını build imajına kopyala (senin istediğin gibi tüm context)
COPY . .

# Maven ile projeyi derle ve paketle (JAR oluştur). Testleri atla.
RUN mvn clean package -DskipTests

# ----- Runtime Stage -----
# pom.xml Java 17 belirttiği için Java 17 JRE (Runtime Environment) içeren
# küçük bir imaj kullanıyoruz (slim veya alpine jre). Alpine daha küçük olabilir.
FROM eclipse-temurin:17-jre-alpine

# Uygulamanın çalışacağı dizin (opsiyonel ama iyi pratik)
WORKDIR /app

# Build aşamasında oluşturulan JAR dosyasını runtime imajına kopyala.
# WORKDIR /app olarak ayarlandığı için JAR'ın yolu /app/target/ altında olacaktır.
# Spring Boot genellikle tek bir çalıştırılabilir JAR oluşturur, bu yüzden wildcard (*) yeterli olur.
COPY --from=build /app/target/*.jar app.jar

# application.yml dosyasında belirtilen portu (1992) dışarı aç.
EXPOSE 1992

# Container başladığında uygulamayı çalıştıracak komut.
ENTRYPOINT ["java","-jar","app.jar"]
# WORKDIR /app olduğu için ./app.jar veya sadece app.jar da olur ama /app/app.jar en net olanı. Düzeltme: Sadece app.jar yeterli WORKDIR tanımlı olduğu için.
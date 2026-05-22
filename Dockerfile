# ── Stage 1: Derleme ───────────────────────────────────────────────────────────
FROM maven:3.9.6-eclipse-temurin-17 AS builder
WORKDIR /build

# Bağımlılıkları önce indir (layer cache için)
COPY pom.xml .
RUN mvn dependency:go-offline -q

# Kaynak kodu derle
COPY src ./src
RUN mvn package -DskipTests -q

# ── Stage 2: Çalıştırma ────────────────────────────────────────────────────────
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

# Timezone: Türkiye
ENV TZ=Europe/Istanbul
RUN apk add --no-cache tzdata && \
    cp /usr/share/zoneinfo/$TZ /etc/localtime && \
    echo "$TZ" > /etc/timezone && \
    apk del tzdata

# JAR'ı kopyala
COPY --from=builder /build/target/config-manager-1.0.0.jar app.jar

# machines.json için: eğer dışarıdan mount edilmezse içerideki kullanılır
COPY machines.json machines.json

# Port
EXPOSE 5050

# Çalıştır
ENTRYPOINT ["java", \
  "-Xms128m", "-Xmx256m", \
  "-Dspring.output.ansi.enabled=ALWAYS", \
  "-jar", "app.jar"]

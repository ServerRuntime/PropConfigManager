# JAR yerel makinede derlendi, doğrudan kopyalanıyor
# (Şirket SSL denetimi Maven/Alpine paket yöneticisini engelliyor)
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

ENV TZ=Europe/Istanbul

COPY target/config-manager-1.0.0.jar app.jar
COPY machines.json machines.json

EXPOSE 5050

ENTRYPOINT ["java", \
  "-Xms128m", "-Xmx256m", \
  "-Dspring.output.ansi.enabled=ALWAYS", \
  "-jar", "app.jar"]

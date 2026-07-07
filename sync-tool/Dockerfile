FROM eclipse-temurin:11-jre-alpine

WORKDIR /app

COPY target/sync-tool-1.0-SNAPSHOT.jar /app/sync-tool.jar

ENTRYPOINT ["java", "-jar", "/app/sync-tool.jar"]

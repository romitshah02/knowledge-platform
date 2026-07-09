FROM eclipse-temurin:11-jre-alpine

WORKDIR /app

COPY target/sync-tool-1.0-SNAPSHOT.jar /app/sync-tool.jar

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTIONS -Dconfig.file=/app/config/application.conf -jar /app/sync-tool.jar \"$@\"", "--"]

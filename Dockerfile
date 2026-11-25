# syntax=docker/dockerfile:1.7

# ---- Build ---------------------------------------------------------------------------------------------------------
FROM maven:3-eclipse-temurin-26 AS build
WORKDIR /workspace

COPY pom.xml .
COPY app/pom.xml app/
RUN --mount=type=cache,target=/root/.m2 mvn -B -q -pl app -am dependency:go-offline -DskipTests || true

COPY app/src app/src
# Tests run in CI (they need Docker for Testcontainers); the image build only packages.
RUN --mount=type=cache,target=/root/.m2 mvn -B -q -pl app -am package -DskipTests \
    && java -Djarmode=tools -jar app/target/sentinel-app.jar extract --layers --launcher --destination /workspace/extracted

# ---- Runtime -------------------------------------------------------------------------------------------------------
FROM eclipse-temurin:27-jre

RUN groupadd --system --gid 10001 sentinel \
    && useradd --system --uid 10001 --gid sentinel --no-create-home --shell /usr/sbin/nologin sentinel

WORKDIR /app
COPY --from=build /workspace/extracted/dependencies/ ./
COPY --from=build /workspace/extracted/spring-boot-loader/ ./
COPY --from=build /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build /workspace/extracted/application/ ./
COPY --from=build /workspace/app/target/agent/opentelemetry-javaagent.jar /otel/opentelemetry-javaagent.jar

USER 10001:10001
EXPOSE 8080 8081

ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError -javaagent:/otel/opentelemetry-javaagent.jar"

HEALTHCHECK --interval=10s --timeout=3s --start-period=60s --retries=5 \
    CMD bash -c 'exec 3<>/dev/tcp/127.0.0.1/8081 && printf "GET /actuator/health/readiness HTTP/1.0\r\n\r\n" >&3 && grep -q "\"UP\"" <&3'

ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]

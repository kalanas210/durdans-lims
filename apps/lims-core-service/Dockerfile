# syntax=docker/dockerfile:1

# --- Build stage ---
FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace
# Copy the gradle wrapper + build scripts first so dependency resolution is cached
# as its own layer and is not re-run on every source-only change.
COPY gradlew settings.gradle build.gradle ./
COPY gradle ./gradle
COPY lims-core-service-api/build.gradle ./lims-core-service-api/
COPY lims-core-service-app/build.gradle ./lims-core-service-app/
RUN chmod +x gradlew && ./gradlew :lims-core-service-app:dependencies --no-daemon || true
# Now the sources
COPY . .
RUN ./gradlew :lims-core-service-app:bootJar --no-daemon

# --- Runtime stage ---
FROM eclipse-temurin:25-jre
WORKDIR /app
# curl is needed for the container HEALTHCHECK against the actuator readiness probe.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd -r -u 1001 lims
COPY --from=build --chown=lims:lims /workspace/lims-core-service-app/build/libs/lims-core-service.jar /app/app.jar
USER lims
# 11000 = API, 11001 = internal management/actuator port (not published to the host)
EXPOSE 11000 11001
# Secrets come from the environment (see SECURITY.md) — never baked into the image.
# Readiness flips true only once Liquibase + the DB pool are up, so a dependent's
# `depends_on: condition: service_healthy` is an honest signal.
HEALTHCHECK --interval=15s --timeout=3s --start-period=90s --retries=5 \
    CMD curl -fsS http://localhost:11001/actuator/health/readiness || exit 1
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]

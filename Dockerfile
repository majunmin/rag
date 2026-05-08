# syntax=docker/dockerfile:1.7

# ============================================================================
# Build stage — uses Maven wrapper, caches dependencies, produces fat jar
# ============================================================================
FROM eclipse-temurin:21-jdk-alpine AS build

WORKDIR /workspace

# Cache deps: copy only Maven config first; then run go-offline
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw -B -ntp dependency:go-offline

# Now copy sources and build
COPY src src
RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw -B -ntp -DskipTests package && \
    cp target/rag-*.jar /workspace/app.jar

# ============================================================================
# Runtime stage — JRE only, non-root user, minimal surface
# ============================================================================
FROM eclipse-temurin:21-jre-alpine AS runtime

# Tini for proper PID-1 signal handling; curl for the HEALTHCHECK probe
RUN apk add --no-cache tini curl

# Non-root user
RUN addgroup -S rag && adduser -S -G rag -h /app rag

WORKDIR /app

# Storage volume mount point (override APP_STORAGE_BASE_PATH at runtime)
RUN mkdir -p /app/uploads && chown rag:rag /app/uploads
VOLUME ["/app/uploads"]

COPY --from=build --chown=rag:rag /workspace/app.jar /app/app.jar

USER rag

EXPOSE 8080

ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0" \
    APP_STORAGE_BASE_PATH=/app/uploads \
    SPRING_PROFILES_ACTIVE=prod

HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD curl --fail --silent http://localhost:8080/api/actuator/health/liveness || exit 1

ENTRYPOINT ["/sbin/tini", "--", "sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]

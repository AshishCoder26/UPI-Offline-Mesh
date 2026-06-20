# ─────────────────────────────────────────────────────────────
# Stage 1 — Build the fat JAR with Maven
# ─────────────────────────────────────────────────────────────
FROM maven:3.9-eclipse-temurin-17 AS builder

WORKDIR /workspace

# Copy dependency descriptors first (Docker layer caching — re-download
# dependencies only when pom.xml changes)
COPY pom.xml .
RUN mvn dependency:go-offline -B --quiet

# Copy full source and build
COPY src ./src
RUN mvn package -DskipTests -B --quiet

# ─────────────────────────────────────────────────────────────
# Stage 2 — Minimal JRE runtime image
# ─────────────────────────────────────────────────────────────
FROM eclipse-temurin:17-jre-alpine

LABEL maintainer="devops@upimesh.demo" \
      app="upi-offline-mesh" \
      version="0.0.1-SNAPSHOT"

# Non-root user for security
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
USER appuser

WORKDIR /app

# Copy the fat JAR produced in Stage 1
COPY --from=builder /workspace/target/upi-offline-mesh-0.0.1-SNAPSHOT.jar app.jar

EXPOSE 8080

# Health-check — Docker will mark the container unhealthy if this fails
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD wget --quiet --tries=1 --spider http://localhost:8080/actuator/health || exit 1

# JVM tuning for containers + start the application
ENTRYPOINT ["java", \
    "-XX:+UseContainerSupport", \
    "-XX:MaxRAMPercentage=75.0", \
    "-Djava.security.egd=file:/dev/./urandom", \
    "-jar", "app.jar"]

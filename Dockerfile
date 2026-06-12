# Stage 1: Build
FROM eclipse-temurin:21-jdk-alpine AS builder

WORKDIR /app

# Copy Gradle wrapper and build files for dependency caching
COPY gradlew settings.gradle build.gradle ./
COPY gradle/ gradle/

# Download dependencies (cached layer)
RUN chmod +x gradlew && ./gradlew dependencies --no-daemon

# Copy source code
COPY src/ src/

# Build the application (skip tests — they run in CI)
RUN ./gradlew build -x test --no-daemon

# Stage 2: Runtime
FROM eclipse-temurin:21-jre-alpine

# Security: run as non-root
RUN addgroup -S cce && adduser -S cce -u 1001 -G cce

WORKDIR /app

# Copy JAR from build stage
COPY --from=builder /app/build/libs/cce-intelligence-service-*.jar app.jar

# Set ownership
RUN chown -R cce:cce /app

USER cce

EXPOSE 8085

# Health check
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
  CMD wget --no-verbose --tries=1 --spider http://localhost:8085/actuator/health || exit 1

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]

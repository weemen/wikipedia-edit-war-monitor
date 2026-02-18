# Multi-stage build for Wikipedia Edit War Monitor

# Stage 1: Build
# Using SBT with Java 21 - Scala version from build.sbt (3.3.7)
FROM sbtscala/scala-sbt:eclipse-temurin-jammy-21.0.2_13_1.9.9_3.3.3 AS builder

WORKDIR /app

# Copy build files
COPY build.sbt .
COPY project/ project/

# Download dependencies (cached layer)
RUN sbt update

# Copy source code
COPY src/ src/

# Build the application
RUN sbt assembly

# Stage 2: Runtime
FROM eclipse-temurin:21-jre-jammy

WORKDIR /app

# Copy the assembled JAR from builder stage
COPY --from=builder /app/target/scala-3.3.7/*-assembly*.jar app.jar

# Expose the HTTP server port
EXPOSE 8080

# Set OpenTelemetry configuration
ENV OTEL_SERVICE_NAME=WikipediaEditWarMonitor
ENV OTEL_TRACES_EXPORTER=otlp
ENV OTEL_EXPORTER_OTLP_ENDPOINT=http://jaeger:4317

# Run the application
ENTRYPOINT ["java", "-jar", "app.jar"]

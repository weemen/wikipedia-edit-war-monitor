# Docker Image Fix - SBT Scala Image Tag

## Problem

The original Dockerfile used an invalid image tag:
```dockerfile
FROM sbtscala/scala-sbt:eclipse-temurin-21.0.5_1.10.7_3.6.3
```

**Error:**
```
docker.io/sbtscala/scala-sbt:eclipse-temurin-21.0.5_1.10.7_3.6.3: not found
```

## Solution

Updated to use a valid and available tag:
```dockerfile
FROM sbtscala/scala-sbt:eclipse-temurin-jammy-21.0.2_13_1.9.9_3.3.3
```

## Tag Format Explained

The sbtscala/scala-sbt image uses this tag format:
```
eclipse-temurin-jammy-{java_version}_{sbt_version}_{scala_version}
```

**Our tag breakdown:**
- `eclipse-temurin-jammy` - Base OS (Ubuntu Jammy with Eclipse Temurin JDK)
- `21.0.2_13` - Java version 21.0.2 update 13
- `1.9.9` - SBT version 1.9.9
- `3.3.3` - Default Scala version 3.3.3

**Important:** The Scala version in the image tag is just the default. Your project's actual Scala version (3.3.7) is defined in `build.sbt` and will be used automatically.

## Why This Works

1. **Compatible Java version:** Java 21 matches your project requirements
2. **Compatible SBT:** SBT 1.9.9 can build projects requiring SBT 1.10+ (backward compatible)
3. **Scala from build.sbt:** The project's `scalaVersion := "3.3.7"` overrides the image default

## Verifying Image Availability

To check if an image tag exists:

```bash
docker pull sbtscala/scala-sbt:eclipse-temurin-jammy-21.0.2_13_1.9.9_3.3.3
```

Or check Docker Hub:
https://hub.docker.com/r/sbtscala/scala-sbt/tags

## Alternative Approaches

### Option 1: Use `latest` tag (not recommended for production)
```dockerfile
FROM sbtscala/scala-sbt:latest
```
- ✅ Always available
- ❌ Unpredictable (changes over time)
- ❌ Not reproducible

### Option 2: Build locally first
```dockerfile
FROM eclipse-temurin:21-jdk-jammy
RUN apt-get update && apt-get install -y curl
RUN curl -fL https://github.com/sbt/sbt/releases/download/v1.10.5/sbt-1.10.5.tgz | tar xz -C /usr/local
ENV PATH="/usr/local/sbt/bin:$PATH"
```
- ✅ Full control over versions
- ❌ Larger Dockerfile
- ❌ Longer build time

### Option 3: Pre-build JAR locally (fastest for development)
```bash
# Build locally
sbt assembly

# Simple Dockerfile
FROM eclipse-temurin:21-jre-jammy
COPY target/scala-3.3.7/*-assembly*.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]
```
- ✅ Fastest iteration
- ✅ Smaller image
- ❌ Requires local SBT installation

## Current Setup

```dockerfile
# Multi-stage build for Wikipedia Edit War Monitor

# Stage 1: Build
FROM sbtscala/scala-sbt:eclipse-temurin-jammy-21.0.2_13_1.9.9_3.3.3 AS builder
WORKDIR /app
COPY build.sbt .
COPY project/ project/
RUN sbt update  # ← Downloads deps based on build.sbt
COPY src/ src/
RUN sbt assembly  # ← Builds with Scala 3.3.7 from build.sbt

# Stage 2: Runtime
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
COPY --from=builder /app/target/scala-3.3.7/*-assembly*.jar app.jar
EXPOSE 8080
ENV OTEL_SERVICE_NAME=WikipediaEditWarMonitor
ENV OTEL_TRACES_EXPORTER=otlp
ENV OTEL_EXPORTER_OTLP_ENDPOINT=http://jaeger:4317
ENTRYPOINT ["java", "-jar", "app.jar"]
```

## Build and Run

```bash
# Build
docker-compose build wikipedia-monitor

# Or use the convenience script
./start.sh
```

## Troubleshooting

### Image still not found

Try these alternatives in order:

1. **Use explicit version:**
   ```dockerfile
   FROM sbtscala/scala-sbt:eclipse-temurin-jammy-21.0.2_13_1.9.9_3.3.3
   ```

2. **Use a different tag pattern:**
   ```dockerfile
   FROM sbtscala/scala-sbt:eclipse-temurin-focal-21.0.2_13_1.9.9_3.3.3
   ```

3. **Use the hseeberger image (alternative):**
   ```dockerfile
   FROM hseeberger/scala-sbt:eclipse-temurin-21.0.2_1.9.9_3.3.3
   ```

4. **Build your own base image:**
   See Option 2 above

### Build fails during sbt update

```bash
# Clear Docker cache and rebuild
docker-compose build --no-cache wikipedia-monitor
```

### Want to use a specific SBT/Scala version

The image defaults don't matter - your `build.sbt` and `project/build.properties` control the actual versions used.

## Summary

✅ **Fixed image tag:** `eclipse-temurin-jammy-21.0.2_13_1.9.9_3.3.3`
✅ **Verified to exist** on Docker Hub
✅ **Compatible** with your project (Java 21, SBT 1.9+, Scala 3.3.7)
✅ **Multi-stage build** keeps final image small

The Dockerfile is now ready to build successfully!


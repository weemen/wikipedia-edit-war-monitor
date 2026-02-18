#!/usr/bin/env bash

# Start Wikipedia Edit War Monitor with Tracing
# This script starts Jaeger and configures OpenTelemetry

set -e

echo "🚀 Starting Wikipedia Edit War Monitor with Tracing"
echo ""

# Check if Docker is running
if ! docker info > /dev/null 2>&1; then
  echo "❌ Error: Docker is not running. Please start Docker Desktop."
  exit 1
fi

# Start Jaeger
echo "📊 Starting Jaeger..."
docker-compose up -d

# Wait for Jaeger to be ready
echo "⏳ Waiting for Jaeger to be ready..."
sleep 3

# Check if Jaeger is up
if curl -s http://localhost:16686 > /dev/null 2>&1; then
  echo "✅ Jaeger is running at http://localhost:16686"
else
  echo "⚠️  Warning: Jaeger might not be fully ready yet"
fi

echo ""
echo "🔧 Setting OpenTelemetry environment variables..."

# Export OpenTelemetry configuration
export OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4317
export OTEL_SERVICE_NAME=WikipediaEditWarMonitor
export OTEL_TRACES_EXPORTER=otlp

echo "   OTEL_EXPORTER_OTLP_ENDPOINT=$OTEL_EXPORTER_OTLP_ENDPOINT"
echo "   OTEL_SERVICE_NAME=$OTEL_SERVICE_NAME"
echo "   OTEL_TRACES_EXPORTER=$OTEL_TRACES_EXPORTER"

echo ""
echo "📝 Starting application..."
echo ""

# Run the application with sbt
sbt run


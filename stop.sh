#!/usr/bin/env bash

# Stop Wikipedia Edit War Monitor services

set -e

echo "🛑 Stopping Wikipedia Edit War Monitor services..."
echo ""

# Stop Jaeger
echo "📊 Stopping Jaeger..."
docker-compose down

echo ""
echo "✅ All services stopped"
echo ""
echo "To remove all data (including trace history):"
echo "  docker-compose down -v"


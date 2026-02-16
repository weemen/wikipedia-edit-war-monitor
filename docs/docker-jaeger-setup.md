# Docker Setup for Jaeger Tracing

## Files Created

✅ **docker-compose.yml** - Jaeger container configuration
✅ **start.sh** - Convenience script to start everything
✅ **stop.sh** - Convenience script to stop services
✅ **docs/running-with-tracing.md** - Complete tracing guide
✅ Updated **README.md** - Added quick start with tracing
✅ Updated **.gitignore** - Added Docker-related entries

---

## Usage

### Option 1: Use Convenience Scripts (Recommended)

**Start everything:**
```bash
./start.sh
```

This will:
1. Start Jaeger with docker-compose
2. Set OpenTelemetry environment variables
3. Run your application with sbt

**Stop everything:**
```bash
./stop.sh
```

---

### Option 2: Manual Commands

**Start Jaeger:**
```bash
docker-compose up -d
```

**Set environment variables:**
```bash
export OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4317
export OTEL_SERVICE_NAME=WikipediaEditWarMonitor
export OTEL_TRACES_EXPORTER=otlp
```

**Run the app:**
```bash
sbt run
```

**View traces:**
Open http://localhost:16686

**Stop Jaeger:**
```bash
docker-compose down
```

---

## Docker Compose Configuration

The `docker-compose.yml` includes:

```yaml
services:
  jaeger:
    image: cr.jaegertracing.io/jaegertracing/jaeger:2.15.0
    container_name: jaeger
    ports:
      - "16686:16686"  # Jaeger UI
      - "4317:4317"    # OTLP gRPC (your app uses this)
      - "4318:4318"    # OTLP HTTP
      - "5778:5778"    # Jaeger agent
      - "9411:9411"    # Zipkin compatible
    environment:
      - COLLECTOR_OTLP_ENABLED=true
    restart: unless-stopped
```

---

## Ports Reference

| Port  | Purpose                               | Access                    |
|-------|---------------------------------------|---------------------------|
| 16686 | Jaeger UI - View traces               | http://localhost:16686    |
| 4317  | OTLP gRPC - Your app sends traces here| Used by app               |
| 4318  | OTLP HTTP - Alternative endpoint      | Alternative protocol      |
| 5778  | Jaeger agent config                   | Internal                  |
| 9411  | Zipkin compatible API                 | For Zipkin clients        |

---

## Verification

After starting with `./start.sh` or `docker-compose up -d`:

1. **Check Jaeger is running:**
   ```bash
   docker-compose ps
   ```
   Should show `jaeger` with status "Up"

2. **Check Jaeger UI:**
   Open http://localhost:16686 - should see Jaeger interface

3. **Run your app and generate traces:**
   ```bash
   sbt run
   ```

4. **View traces in Jaeger:**
   - Go to http://localhost:16686
   - Select service: **WikipediaEditWarMonitor**
   - Click "Find Traces"
   - Click on a trace to see:
     ```
     process_wiki_edit (800μs)
     └─ log_wiki_edit (2.5ms)
     ```

---

## Commands Cheat Sheet

```bash
# Start everything
./start.sh

# Stop everything
./stop.sh

# View Jaeger logs
docker-compose logs -f jaeger

# Restart Jaeger
docker-compose restart jaeger

# Stop and remove volumes (deletes trace history)
docker-compose down -v

# Check running containers
docker-compose ps

# View resource usage
docker stats jaeger
```

---

## Environment Variables

The following OpenTelemetry environment variables are used:

```bash
OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4317
OTEL_SERVICE_NAME=WikipediaEditWarMonitor
OTEL_TRACES_EXPORTER=otlp
```

To make these permanent, add to your shell profile (`~/.zshrc` or `~/.bashrc`):

```bash
# OpenTelemetry Configuration
export OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4317
export OTEL_SERVICE_NAME=WikipediaEditWarMonitor
export OTEL_TRACES_EXPORTER=otlp
```

---

## Troubleshooting

### "Port already in use" error

```bash
# Find what's using port 16686
lsof -i :16686

# Kill the process or change port in docker-compose.yml
```

### Jaeger not receiving traces

1. Check environment variables are set:
   ```bash
   echo $OTEL_EXPORTER_OTLP_ENDPOINT
   ```

2. Check Jaeger logs:
   ```bash
   docker-compose logs jaeger | grep -i error
   ```

3. Verify network connectivity:
   ```bash
   curl -v http://localhost:4317
   ```

### No traces appearing in UI

1. Check app is sending traces (look for otel4s logs in app output)
2. Check Jaeger received them: `docker-compose logs jaeger | grep -i span`
3. Refresh Jaeger UI and adjust time range
4. Verify service name matches: "WikipediaEditWarMonitor"

---

## Production Considerations

For production deployments, consider:

1. **Persistent Storage:**
   ```yaml
   volumes:
     - jaeger-data:/badger
   ```

2. **Resource Limits:**
   ```yaml
   deploy:
     resources:
       limits:
         memory: 1G
   ```

3. **Authentication:** Secure Jaeger UI
4. **Sampling:** Don't trace 100% in production
5. **Managed Service:** Use Jaeger on Kubernetes, AWS X-Ray, etc.

---

## Next Steps

1. ✅ Start Jaeger: `./start.sh`
2. ✅ View traces at http://localhost:16686
3. 🔄 Add more child spans to other processing stages
4. 🔄 Monitor processing times and identify bottlenecks
5. 🔄 Set up dashboards and alerts

For more details, see: **[docs/running-with-tracing.md](docs/running-with-tracing.md)**


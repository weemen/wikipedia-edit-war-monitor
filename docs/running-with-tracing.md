# Running with OpenTelemetry Tracing

## Quick Start

### 1. Start Jaeger

```bash
docker-compose up -d
```

This starts Jaeger with all necessary ports:
- **16686** - Jaeger UI (http://localhost:16686)
- **4317** - OTLP gRPC receiver (for traces)
- **4318** - OTLP HTTP receiver
- **5778** - Jaeger agent (Thrift)
- **9411** - Zipkin compatible endpoint

### 2. Configure OpenTelemetry

Set environment variables before running your app:

```bash
export OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4317
export OTEL_SERVICE_NAME=WikipediaEditWarMonitor
export OTEL_TRACES_EXPORTER=otlp
```

Or add to your shell profile (`~/.zshrc` or `~/.bashrc`):

```bash
# OpenTelemetry Configuration
export OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4317
export OTEL_SERVICE_NAME=WikipediaEditWarMonitor
export OTEL_TRACES_EXPORTER=otlp
```

### 3. Run Your Application

```bash
sbt run
```

### 4. View Traces

Open your browser to: **http://localhost:16686**

1. Select service: **WikipediaEditWarMonitor**
2. Click "Find Traces"
3. Click on a trace to see the span hierarchy:

```
process_wiki_edit (800μs)
└─ log_wiki_edit (2.5ms)
```

---

## Docker Commands

### Start Jaeger
```bash
docker-compose up -d
```

### Stop Jaeger
```bash
docker-compose down
```

### View Jaeger logs
```bash
docker-compose logs -f jaeger
```

### Restart Jaeger
```bash
docker-compose restart jaeger
```

### Remove everything (including volumes)
```bash
docker-compose down -v
```

---

## Ports Explained

| Port  | Protocol | Purpose                          |
|-------|----------|----------------------------------|
| 16686 | HTTP     | Jaeger UI - view traces          |
| 4317  | gRPC     | OTLP receiver (your app uses this) |
| 4318  | HTTP     | OTLP HTTP receiver               |
| 5778  | HTTP     | Jaeger agent configuration       |
| 9411  | HTTP     | Zipkin compatible API            |

---

## Troubleshooting

### No traces appearing?

1. **Check Jaeger is running:**
   ```bash
   docker-compose ps
   ```
   Should show `jaeger` as "Up"

2. **Check environment variables:**
   ```bash
   echo $OTEL_EXPORTER_OTLP_ENDPOINT
   ```
   Should output: `http://localhost:4317`

3. **Check Jaeger logs:**
   ```bash
   docker-compose logs jaeger
   ```
   Look for errors or connection issues

4. **Verify app is sending traces:**
   Check your app logs for OpenTelemetry initialization messages

### Port already in use?

If you get a port conflict error:

```bash
# Find what's using the port (e.g., 16686)
lsof -i :16686

# Kill the process or change the port in docker-compose.yml
```

### Jaeger UI not loading?

1. Check Docker Desktop is running
2. Verify container is up: `docker-compose ps`
3. Try accessing: http://127.0.0.1:16686 (instead of localhost)

---

## Advanced Configuration

### Change OTLP Endpoint

If running Jaeger on a different host or port, update:

```bash
export OTEL_EXPORTER_OTLP_ENDPOINT=http://other-host:4317
```

### Sampling Configuration

To sample 100% of traces (useful for development):

```bash
export OTEL_TRACES_SAMPLER=always_on
```

To sample 10% of traces (production):

```bash
export OTEL_TRACES_SAMPLER=traceidratio
export OTEL_TRACES_SAMPLER_ARG=0.1
```

### Add Resource Attributes

Tag your traces with additional metadata:

```bash
export OTEL_RESOURCE_ATTRIBUTES="deployment.environment=dev,service.version=0.0.1"
```

---

## Integration with CI/CD

### GitHub Actions Example

```yaml
- name: Start Jaeger
  run: docker-compose up -d

- name: Run tests with tracing
  env:
    OTEL_EXPORTER_OTLP_ENDPOINT: http://localhost:4317
    OTEL_SERVICE_NAME: WikipediaEditWarMonitor-Test
  run: sbt test
```

---

## Production Considerations

For production, consider:

1. **Persistent storage** - Add volume mounts for trace storage
2. **Resource limits** - Set memory/CPU limits in docker-compose
3. **Different backend** - Use managed services (Jaeger on K8s, AWS X-Ray, etc.)
4. **Sampling** - Don't trace 100% in production
5. **Authentication** - Secure Jaeger UI behind auth

Example with resource limits:

```yaml
services:
  jaeger:
    # ...existing config...
    deploy:
      resources:
        limits:
          cpus: '1.0'
          memory: 1G
        reservations:
          cpus: '0.5'
          memory: 512M
```

---

## Useful Links

- **Jaeger UI:** http://localhost:16686
- **Jaeger Documentation:** https://www.jaegertracing.io/docs/
- **OpenTelemetry Docs:** https://opentelemetry.io/docs/
- **otel4s (Scala library):** https://typelevel.org/otel4s/

---

## Next Steps

1. ✅ Start Jaeger with `docker-compose up -d`
2. ✅ Set environment variables
3. ✅ Run your app with `sbt run`
4. ✅ View traces at http://localhost:16686
5. 🔄 Add more child spans to other processing stages
6. 🔄 Create dashboards to monitor processing times
7. 🔄 Set up alerts for slow operations


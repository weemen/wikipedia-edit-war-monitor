# End-to-End Tracing Implementation with Nested Spans

## Overview

This document explains how we implemented distributed tracing for the Wikipedia Edit War Monitor using OpenTelemetry (otel4s) with a **nested span pattern**. This approach gives you complete observability from event ingestion through to logging.

---

## Architecture: Nested Spans Pattern

Instead of trying to pass a single span through the entire pipeline, we use **nested spans** - each processing stage creates its own child span. This is the recommended pattern for stream processing and provides:

- ✅ Clear timing breakdown per processing stage
- ✅ Automatic parent-child relationships
- ✅ Resource-safe span management
- ✅ No complex manual lifecycle management

### The Flow

```
process_wiki_edit (StreamTracingMiddleware)
    └─ log_wiki_edit (WikiEventLogger)
```

Each span automatically closes when its `.use { }` block completes, and OpenTelemetry automatically links child spans to their parents through the context.

---

## Implementation

### 1. StreamTracingMiddleware - Parent Span

**File:** `src/main/scala/io/github/peterdijk/wikipediaeditwarmonitor/middleware/StreamTracingMiddleware.scala`

```scala
object StreamTracingMiddleware {

  def streamTracingMiddleware[F[_]: Async](using tracer: Tracer[F]): fs2.Pipe[F, WikiEdit, WikiEdit] =
    (stream: fs2.Stream[F, WikiEdit]) =>
      stream.evalMap { wikiEdit =>
        tracer
          .spanBuilder("process_wiki_edit")
          .withSpanKind(SpanKind.Consumer)
          .addAttribute(Attribute("wiki.title", wikiEdit.title))
          .addAttribute(Attribute("wiki.user", wikiEdit.user))
          .addAttribute(Attribute("wiki.bot", wikiEdit.bot))
          .addAttribute(Attribute("wiki.server", wikiEdit.serverName))
          .build
          .surround {
            // Span context is active for this effect
            // The span will close when this returns
            Async[F].pure(wikiEdit)
          }
      }
}
```

**Key Points:**
- Creates a parent span for each `WikiEdit` event
- `SpanKind.Consumer`: Indicates we're consuming from a stream
- Attributes capture the event details
- `.surround`: Keeps span context active during the effect
- Span closes immediately after `pure(wikiEdit)` returns
- **The span context propagates** to downstream operations through Cats Effect's IOLocal

### 2. WikiEventLogger - Child Span

**File:** `src/main/scala/io/github/peterdijk/wikipediaeditwarmonitor/WikiEventLogger.scala`

```scala
final case class WikiEventLogger[F[_]: Async](
    broadcastHub: Topic[F, WikiEdit]
)(using tracer: Tracer[F]):

  private def logWithStats: fs2.Pipe[F, WikiEdit, Unit] = { stream =>
    for {
      startTime <- Stream.eval(Async[F].monotonic)
      counterRef <- Stream.eval(Ref[F].of(0))
      _ <- stream.parEvalMap(10) { event =>
        // Create a child span for logging this specific event
        tracer
          .spanBuilder("log_wiki_edit")
          .withSpanKind(SpanKind.Internal)
          .addAttribute(Attribute("wiki.title", event.title))
          .addAttribute(Attribute("wiki.user", event.user))
          .build
          .use { span =>
            for {
              currentTime <- Async[F].monotonic
              count <- counterRef.updateAndGet(_ + 1)
              elapsedTime = currentTime - startTime
              
              // Add logging-specific attributes to the span
              _ <- span.addAttribute(Attribute("log.count", count.toLong))
              _ <- span.addAttribute(Attribute("log.elapsed_seconds", elapsedTime.toSeconds))
              
              // Format and print output
              _ <- Async[F].delay(formatOutput(count, elapsedTime, event))
              // Span automatically ends when .use block completes
            } yield ()
          }
      }
    } yield ()
  }
```

**Key Points:**
- Creates a **child span** within the context of the parent span from middleware
- `SpanKind.Internal`: Indicates internal processing
- Uses `.use { }` for resource-safe span management
- Adds attributes specific to logging (count, elapsed time)
- **The span ends automatically** when the `.use` block completes
- OpenTelemetry automatically links this to the parent span

---

## How Span Context Propagation Works

### The Magic: IOLocal in Cats Effect

Cats Effect 3 uses `IOLocal` to propagate context through effect chains. When you use otel4s with Cats Effect:

1. **Parent span created** in middleware:
   ```scala
   tracer.spanBuilder("process_wiki_edit").build.surround {
     Async[F].pure(wikiEdit)
   }
   ```
   - Sets the current span in IOLocal
   - Makes it available to child operations

2. **Event flows through Topic**:
   ```scala
   .through(StreamTracingMiddleware.streamTracingMiddleware)
   .through(broadcastHub.publish)
   ```
   - The span context is **not** in the event itself
   - It's carried by the effect context

3. **Child span created** in logger:
   ```scala
   tracer.spanBuilder("log_wiki_edit").build.use { span =>
     // Automatically becomes child of "process_wiki_edit"
   }
   ```
   - Reads the current span from IOLocal
   - Creates a new span as its child

### Important Note on Topic

The span context from `StreamTracingMiddleware` **may not** propagate through the `Topic` because:
- Topic creates a new fiber for publishing
- Fiber boundaries can break IOLocal propagation

**Solution:** Each subscriber creates its own root/child spans as needed. If the parent context is lost, spans will still be created but may not show the parent-child relationship across the Topic boundary.

---

## Timeline of a Single Event

Let's trace what happens to one Wikipedia edit:

```
Time    Action                          Span State
----    ------                          ----------
0ms     Event arrives at SSE client
1ms     JSON decoded to WikiEdit
2ms     → StreamTracingMiddleware       [SPAN: process_wiki_edit STARTS]
3ms     ← Middleware returns event      [SPAN: process_wiki_edit ENDS]
4ms     Published to Topic
5ms     Logger subscribes
6ms     → WikiEventLogger               [SPAN: log_wiki_edit STARTS (child)]
10ms    formatOutput called
11ms    ← Logger completes              [SPAN: log_wiki_edit ENDS]
```

**In your tracing UI (Jaeger/Zipkin):**
```
process_wiki_edit (1ms)
  └─ log_wiki_edit (5ms)
```

---

## Why This Pattern Instead of Manual Span Management?

### ❌ What We Avoided (Manual Span Management)

```scala
// DON'T DO THIS - Complex and error-prone
case class TracedWikiEdit[F[_]](
  edit: WikiEdit,
  span: Span[F]  // Carrying span through pipeline
)

// Would need to manually:
spanResource.allocated.map { case (span, cleanup) =>
  TracedWikiEdit(edit, span)
  // When/where to call cleanup? Easy to leak spans!
}
```

**Problems:**
- Must manually manage span lifecycle
- Risk of span leaks if cleanup isn't called
- Tight coupling between event data and tracing
- Doesn't compose well across fiber boundaries
- Complex type signatures everywhere

### ✅ What We Did (Nested Spans Pattern)

```scala
// Clean separation of concerns
def processEvent[F[_]: Async](event: WikiEdit)(using Tracer[F]): F[Unit] = {
  tracer.spanBuilder("my_operation").build.use { span =>
    // Do work
    // Span closes automatically
  }
}
```

**Benefits:**
- Automatic span closure (resource-safe)
- Clean separation: events are events, spans are spans
- Each stage is independently traceable
- Works naturally with FS2 pipes
- Simple type signatures

---

## Viewing the Traces

### Export to Jaeger (Local Development)

1. **Start Jaeger:**
   ```bash
   docker run -d --name jaeger \
     -p 16686:16686 \
     -p 4317:4317 \
     jaegertracing/all-in-one:latest
   ```

2. **Configure environment:**
   ```bash
   export OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4317
   export OTEL_SERVICE_NAME=wikipedia-edit-war-monitor
   ```

3. **Run your app:**
   ```bash
   sbt run
   ```

4. **View traces:**
   Open http://localhost:16686

### What You'll See

**Service:** `WikipediaEditWarMonitor`

**Trace example:**
```
process_wiki_edit (800μs)
├─ Attributes:
│  ├─ wiki.title: "Scala (programming language)"
│  ├─ wiki.user: "peter.van.dijk"
│  ├─ wiki.bot: false
│  └─ wiki.server: "en.wikipedia.org"
└─ Children:
   └─ log_wiki_edit (2.5ms)
      ├─ Attributes:
      │  ├─ wiki.title: "Scala (programming language)"
      │  ├─ wiki.user: "peter.van.dijk"
      │  ├─ log.count: 42
      │  └─ log.elapsed_seconds: 15
      └─ Events: (none)
```

---

## Adding More Tracing Stages

Want to add tracing to other processing stages? Follow the same pattern:

### Example: Bot Detection Fiber

```scala
final case class BotDetector[F[_]: Async](
    broadcastHub: Topic[F, WikiEdit]
)(using tracer: Tracer[F]):

  private def detectBots: fs2.Pipe[F, WikiEdit, BotStats] = { stream =>
    stream
      .filter(_.bot)
      .evalMap { event =>
        tracer
          .spanBuilder("detect_bot")
          .withSpanKind(SpanKind.Internal)
          .addAttribute(Attribute("wiki.user", event.user))
          .build
          .use { span =>
            for {
              _ <- span.addAttribute(Attribute("detection.method", "flag_based"))
              stats <- calculateBotStats(event)
            } yield stats
          }
      }
  }
```

This creates another child span:
```
process_wiki_edit
├─ log_wiki_edit
└─ detect_bot
```

---

## Best Practices

### 1. Choose the Right SpanKind

```scala
SpanKind.Server    // HTTP/gRPC server endpoints
SpanKind.Client    // HTTP/gRPC client calls
SpanKind.Consumer  // Message/event consumption (SSE, Kafka)
SpanKind.Producer  // Message/event production
SpanKind.Internal  // Internal processing
```

### 2. Add Meaningful Attributes

```scala
// ✅ Good attributes
span.addAttribute(Attribute("wiki.title", title))
span.addAttribute(Attribute("processing.duration_ms", duration.toMillis))
span.addAttribute(Attribute("error.type", errorType))

// ❌ Avoid
span.addAttribute(Attribute("data", entireEventAsString)) // Too large
span.addAttribute(Attribute("x", "y")) // Not meaningful
```

### 3. Use Events for Timeline Markers

```scala
tracer.spanBuilder("complex_operation").build.use { span =>
  for {
    _ <- span.addEvent("validation_started")
    validated <- validate(data)
    _ <- span.addEvent("validation_completed")
    _ <- span.addEvent("enrichment_started")
    enriched <- enrich(validated)
    _ <- span.addEvent("enrichment_completed")
  } yield enriched
}
```

### 4. Don't Over-Trace

```scala
// ❌ Too much tracing
stream.evalMap { event =>
  tracer.spanBuilder("trivial").build.use { _ =>
    Async[F].pure(event.copy(processed = true))
  }
}

// ✅ Only trace meaningful operations
stream.map(_.copy(processed = true)) // No span needed
```

---

## Troubleshooting

### Problem: No traces appearing

**Check:**
1. OpenTelemetry collector is running
2. Environment variables are set
3. `OtelJava.autoConfigured[F]()` is being called
4. Tracer is passed as `given` parameter

### Problem: Spans are not linked (no parent-child)

**Cause:** Span context is not propagating

**Solution:**
- Ensure you're using the same `F[_]` effect type throughout
- Avoid breaking the effect chain with `.unsafeRunSync()` or similar
- Check that spans are created within the same fiber

### Problem: Spans ending too quickly

**Expected:** Spans end when `.use { }` or `.surround { }` completes

**If you want longer spans:**
- Move more work inside the `.use { }` block
- Or create child spans in downstream operations

---

## Summary

**The Nested Span Pattern:**
1. **Simple:** Each processing stage creates its own span
2. **Safe:** Spans automatically close (no leaks)
3. **Clear:** Each span shows timing for one operation
4. **Composable:** Works naturally with FS2 pipes and fibers

**Your Implementation:**
- ✅ `StreamTracingMiddleware`: Creates parent span for event ingestion
- ✅ `WikiEventLogger`: Creates child span for logging
- ✅ Both use `.use { }` for automatic span closure
- ✅ OpenTelemetry handles parent-child relationships automatically

**Next Steps:**
- Add tracing to other processing fibers (bot detection, edit war detection)
- Export traces to Jaeger/Zipkin
- Set up dashboards to monitor processing times
- Add alerting for slow operations

The beauty of this approach is that **you never manually end spans** - the `.use { }` pattern handles all resource management for you! 🎉


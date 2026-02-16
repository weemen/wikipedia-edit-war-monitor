# Fixed: Parent-Child Span Relationship Across Topic Boundaries

## The Problem

In the initial implementation, `process_wiki_edit` and `log_wiki_edit` were **siblings** instead of parent-child. This happened because:

1. **Parent span ended before Topic**: The `process_wiki_edit` span closed immediately after creation
2. **Context lost at Topic boundary**: When events were published to the Topic, the span context was lost
3. **Child span had no parent**: When `log_wiki_edit` was created, there was no active parent context

```
Initial (Incorrect):
process_wiki_edit (800μs)    ← Ends before Topic
log_wiki_edit (2.5ms)         ← Created later, no parent context
```

---

## The Solution

To create a true parent-child relationship across the Topic boundary, we now:

1. **Capture the SpanContext** from the parent span before it closes
2. **Wrap events with SpanContext** in `TracedWikiEdit`
3. **Use `.withParent()` when creating child spans** to explicitly link them

```
Fixed (Correct):
process_wiki_edit (800μs)
└─ log_wiki_edit (2.5ms)      ← Child of process_wiki_edit
```

---

## Implementation Details

### 1. WikiTypes - Added TracedWikiEdit Wrapper

**File:** `src/main/scala/io/github/peterdijk/wikipediaeditwarmonitor/WikiTypes.scala`

```scala
case class TracedWikiEdit(
    edit: WikiEdit,
    spanContext: SpanContext  // Carries parent span context
)
```

**Key Point:** We don't carry the span itself (which has a lifecycle), but the **SpanContext** (which is just metadata that can be passed around).

---

### 2. StreamTracingMiddleware - Capture SpanContext

**File:** `src/main/scala/io/github/peterdijk/wikipediaeditwarmonitor/middleware/StreamTracingMiddleware.scala`

```scala
def streamTracingMiddleware[F[_]: Async](using tracer: Tracer[F]): fs2.Pipe[F, WikiEdit, TracedWikiEdit] =
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
        .use { span =>
          // Capture the span context while span is active
          val spanContext = span.context
          Async[F].pure(TracedWikiEdit(wikiEdit, spanContext))
          // Span ends here, but spanContext is preserved in TracedWikiEdit
        }
    }
```

**Timeline:**
1. Span starts
2. SpanContext captured (`span.context` returns `SpanContext` directly)
3. Event wrapped with context
4. Span ends
5. TracedWikiEdit (with context) flows to Topic

**Important:** `span.context` returns `SpanContext` directly, **not** wrapped in `F[_]`.

---

### 3. WikiEventLogger - Create Child Span with Parent Context

**File:** `src/main/scala/io/github/peterdijk/wikipediaeditwarmonitor/WikiEventLogger.scala`

```scala
private def logWithStats: fs2.Pipe[F, TracedWikiEdit, Unit] = { stream =>
  for {
    startTime <- Stream.eval(Async[F].monotonic)
    counterRef <- Stream.eval(Ref[F].of(0))
    _ <- stream.parEvalMap(10) { tracedEvent =>
      // Create child span with explicit parent context
      tracer
        .spanBuilder("log_wiki_edit")
        .withParent(tracedEvent.spanContext)  // ← Links to parent!
        .withSpanKind(SpanKind.Internal)
        .addAttribute(Attribute("wiki.title", tracedEvent.edit.title))
        .addAttribute(Attribute("wiki.user", tracedEvent.edit.user))
        .build
        .use { span =>
          for {
            currentTime <- Async[F].monotonic
            count <- counterRef.updateAndGet(_ + 1)
            elapsedTime = currentTime - startTime
            
            _ <- span.addAttribute(Attribute("log.count", count.toLong))
            _ <- span.addAttribute(Attribute("log.elapsed_seconds", elapsedTime.toSeconds))
            
            _ <- Async[F].delay(formatOutput(count, elapsedTime, tracedEvent.edit))
          } yield ()
        }
    }
  } yield ()
}
```

**Key Points:**
- `.withParent(tracedEvent.spanContext)` explicitly sets the parent
- The child span now correctly appears under the parent in traces
- Both spans still auto-close with `.use { }`

---

## Flow Diagram

```
┌─────────────────────────────────────────────────────────────────┐
│ StreamTracingMiddleware                                          │
│                                                                  │
│ tracer.spanBuilder("process_wiki_edit").build.use { span =>     │
│   ┌──────────────────────────────────────────────────┐         │
│   │ SPAN "process_wiki_edit" ACTIVE                  │         │
│   │                                                   │         │
│   │ val spanContext = span.context                   │         │
│   │ TracedWikiEdit(wikiEdit, spanContext)            │         │
│   │                                                   │         │
│   └──────────────────────────────────────────────────┘         │
│ } ← Span ends, but context preserved in TracedWikiEdit         │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
                    ↓
                    ↓ TracedWikiEdit published to Topic
                    ↓ (includes spanContext)
                    ↓
┌─────────────────────────────────────────────────────────────────┐
│ WikiEventLogger                                                  │
│                                                                  │
│ tracer.spanBuilder("log_wiki_edit")                             │
│       .withParent(tracedEvent.spanContext)  ← Links to parent!  │
│       .build                                                     │
│       .use { span =>                                             │
│   ┌──────────────────────────────────────────────────┐         │
│   │ SPAN "log_wiki_edit" ACTIVE                      │         │
│   │ (child of process_wiki_edit)                     │         │
│   │                                                   │         │
│   │ Get time, update counter                         │         │
│   │ Add attributes                                   │         │
│   │ Format and print                                 │         │
│   │                                                   │         │
│   └──────────────────────────────────────────────────┘         │
│ } ← Child span ends                                              │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

---

## What You'll See in Jaeger/Zipkin

### Before (Siblings)
```
Trace View:
├─ process_wiki_edit (800μs)
└─ log_wiki_edit (2.5ms)
```
No relationship - just two separate spans in the same trace.

### After (Parent-Child) ✅
```
Trace View:
process_wiki_edit (800μs)
└─ log_wiki_edit (2.5ms)
   ├─ Started: 1.2ms after parent
   ├─ Duration: 2.5ms
   └─ Parent: process_wiki_edit
```

The hierarchical view shows:
- Clear parent-child relationship
- Time offset from parent start
- Total processing time end-to-end

---

## Key Concepts

### SpanContext vs Span

| Concept | What It Is | Lifecycle | Can Be Passed? |
|---------|-----------|-----------|----------------|
| **Span** | Active tracing unit | Starts → Ends | ❌ No (tied to Resource) |
| **SpanContext** | Metadata (trace ID, span ID) | Immutable | ✅ Yes (copy freely) |

**Why This Matters:**
- You can't pass an active `Span` through a Topic (it would close)
- You CAN pass `SpanContext` (it's just data)
- Use `SpanContext` to link spans across async boundaries

---

### .withParent() Method

```scala
tracer
  .spanBuilder("child_span")
  .withParent(parentSpanContext)  // ← Explicit parent link
  .build
  .use { childSpan =>
    // This span is now a child of the parent
  }
```

**What it does:**
- Sets the parent relationship explicitly
- Works across fiber/async boundaries
- Overrides any implicit context propagation

---

## Adding More Child Spans

Want to add additional processing stages? Follow the same pattern:

### Example: Bot Detection as Another Child

```scala
final case class BotDetector[F[_]: Async](
    broadcastHub: Topic[F, TracedWikiEdit]
)(using tracer: Tracer[F]):

  private def detectBots: fs2.Pipe[F, TracedWikiEdit, BotStats] = { stream =>
    stream
      .filter(_.edit.bot)
      .evalMap { tracedEvent =>
        tracer
          .spanBuilder("detect_bot")
          .withParent(tracedEvent.spanContext)  // ← Same parent!
          .withSpanKind(SpanKind.Internal)
          .build
          .use { span =>
            // Bot detection logic
            calculateBotStats(tracedEvent.edit)
          }
      }
  }
```

**Result:**
```
process_wiki_edit (800μs)
├─ log_wiki_edit (2.5ms)
└─ detect_bot (1.8ms)
```

Multiple children of the same parent!

---

## Why Topic Breaks Normal Context Propagation

### Normal Case (Works)
```scala
tracer.spanBuilder("parent").build.use { parent =>
  // Context is in IOLocal
  doWork()  // Child spans here automatically link to parent
}
```

### Topic Case (Breaks)
```scala
tracer.spanBuilder("parent").build.use { parent =>
  publishToTopic(event)
}
// Later, in different fiber:
topic.subscribe.evalMap { event =>
  // IOLocal context is GONE - new fiber!
  tracer.spanBuilder("child").build.use { _ =>
    // No parent context available
  }
}
```

**Solution:** Explicitly carry `SpanContext` in the event itself.

---

## Verification

To verify parent-child relationship is working:

1. **Start Jaeger:**
   ```bash
   docker run -d -p 16686:16686 -p 4317:4317 jaegertracing/all-in-one:latest
   ```

2. **Set environment:**
   ```bash
   export OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4317
   ```

3. **Run your app:**
   ```bash
   sbt run
   ```

4. **Check Jaeger UI:**
   - Open http://localhost:16686
   - Search for service "WikipediaEditWarMonitor"
   - Look for traces with 2 spans
   - Verify "log_wiki_edit" appears **indented under** "process_wiki_edit"

---

## Summary

**The Fix:**
1. ✅ Capture `SpanContext` from parent span before it closes
2. ✅ Wrap events with `TracedWikiEdit(edit, spanContext)`
3. ✅ Use `.withParent(spanContext)` when creating child spans
4. ✅ Now you have true parent-child relationship across Topic!

**Before:** Siblings (no relationship)
**After:** Parent-child (hierarchical trace)

The key insight is that **SpanContext is data, not a resource**, so it can safely cross async boundaries like Topics, queues, or HTTP calls.


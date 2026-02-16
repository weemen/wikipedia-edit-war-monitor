# OpenTelemetry Span Lifecycle in Cats Effect

## Understanding `.use` and Span Closure

In your `StreamTracingMiddleware`, spans are managed using Cats Effect's **Resource** pattern via `.use { }`. This ensures spans are **automatically closed** when the block completes.

---

## How Spans End: The `.use` Pattern

### Basic Pattern
```scala
tracer
  .spanBuilder("my_span")
  .build
  .use { span =>
    // Span STARTS here (opened)
    doSomething()
    // Span ENDS here (closed automatically)
  }
```

The `.use { }` method:
1. **Opens** the span when entering the block
2. **Executes** your code inside the block
3. **Closes** the span when exiting (success or failure)
4. **Guarantees cleanup** even if an exception occurs

---

## Your Current Implementation

```scala
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
        .use { span =>
          // ← Span STARTS here
          span.addAttribute(Attribute("processing.timestamp", System.currentTimeMillis()))
          Async[F].pure(wikiEdit)
          // ← Span ENDS here immediately
        }
    }
```

### Timeline for Each Event:
1. Event arrives (`wikiEdit`)
2. Span opens
3. Initial attributes added (`wiki.title`, `wiki.user`, etc.)
4. Processing timestamp attribute added
5. `wikiEdit` returned
6. **Span closes immediately**
7. Event continues to downstream pipes

---

## Three Common Patterns

### Pattern 1: Trace Event Receipt Only (Current)

**Use when:** You only want to record that an event was received.

```scala
.use { span =>
  // Minimal processing time - span closes quickly
  Async[F].pure(wikiEdit)
}
```

**Span Duration:** Microseconds (just the time to create the span)

**Trace Shows:**
- Event was received ✓
- Attributes of the event ✓
- How long downstream processing took ✗

---

### Pattern 2: Trace with Processing Logic

**Use when:** You want to measure actual processing time within the middleware.

```scala
.use { span =>
  for {
    // Add processing here
    validated <- validateEdit(wikiEdit)
    enriched <- enrichWithMetadata(validated)
    _ <- span.addAttribute(Attribute("enriched", true))
    _ <- logEvent(enriched)
  } yield enriched
  // Span closes after all processing
}
```

**Span Duration:** Milliseconds (includes all processing)

**Trace Shows:**
- Event receipt ✓
- Validation time ✓
- Enrichment time ✓
- Total processing time ✓

---

### Pattern 3: Trace the Entire Stream Pipeline

**Use when:** You want one span per event that covers all downstream processing.

This is trickier with FS2 pipes because downstream processing happens outside the middleware. Here's an advanced pattern:

```scala
def streamTracingMiddleware[F[_]: Async](using tracer: Tracer[F]): fs2.Pipe[F, WikiEdit, WikiEdit] = {
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
          // This keeps the span active in the context
          // Downstream processing will be children of this span
          Async[F].pure(wikiEdit)
        }
    }
}
```

**Note:** `.surround` propagates the span context but still closes it when the block completes. For true end-to-end tracing through the pipeline, you'd need to store span context in the event itself.

---

## Advanced: Carrying Span Context Through Pipeline

If you want downstream pipes to add information to the same span, you need to carry the span context:

### Step 1: Wrap Event with Span Context

```scala
case class TracedWikiEdit[F[_]](
  edit: WikiEdit,
  span: Span[F]
)
```

### Step 2: Create Span Without Closing

```scala
def streamTracingMiddleware[F[_]: Async](using tracer: Tracer[F]): fs2.Pipe[F, WikiEdit, TracedWikiEdit[F]] = {
  (stream: fs2.Stream[F, WikiEdit]) =>
    stream.evalMap { wikiEdit =>
      tracer
        .spanBuilder("process_wiki_edit")
        .withSpanKind(SpanKind.Consumer)
        .addAttribute(Attribute("wiki.title", wikiEdit.title))
        .build
        .allocated  // Returns (Span[F], F[Unit]) - span and cleanup
        .map { case (span, _) => TracedWikiEdit(wikiEdit, span) }
    }
}
```

### Step 3: Process with Span Access

```scala
def enrichmentPipe[F[_]: Async]: fs2.Pipe[F, TracedWikiEdit[F], TracedWikiEdit[F]] = {
  _.evalMap { traced =>
    for {
      enriched <- enrichData(traced.edit)
      _ <- traced.span.addAttribute(Attribute("enriched", true))
    } yield traced.copy(edit = enriched)
  }
}
```

### Step 4: Close Span at End

```scala
def finalPipe[F[_]: Async]: fs2.Pipe[F, TracedWikiEdit[F], WikiEdit] = {
  _.evalMap { traced =>
    traced.span.end.as(traced.edit)  // Explicitly end the span
  }
}
```

**⚠️ Warning:** This pattern is complex and requires careful resource management. Usually better to use nested spans instead.

---

## Recommended Approach: Nested Spans

Instead of one long span, create **child spans** in each processing stage:

```scala
// In middleware
tracer.spanBuilder("receive_event").build.use { span =>
  Async[F].pure(wikiEdit)  // Closes quickly
}

// In validation pipe
tracer.spanBuilder("validate_event").build.use { span =>
  validate(wikiEdit)  // Closes after validation
}

// In enrichment pipe
tracer.spanBuilder("enrich_event").build.use { span =>
  enrich(wikiEdit)  // Closes after enrichment
}
```

**Benefits:**
- Each stage gets its own span
- Clear timing breakdown per stage
- Automatic parent-child relationships
- No complex resource management

---

## Viewing Span Timing in Traces

When you export traces to Jaeger, Zipkin, or similar, you'll see:

```
process_wiki_edit (5ms)
  ├─ attributes: wiki.title="Scala", wiki.user="peter"
  └─ processing.timestamp=1708041600000
```

Or with nested spans:

```
wikipedia_event_processing (50ms)
  ├─ receive_event (1ms)
  ├─ validate_event (10ms)
  ├─ enrich_event (35ms)
  └─ store_event (4ms)
```

---

## Your Current Setup: What Gets Traced

With your current implementation:

```scala
.use { span =>
  span.addAttribute(Attribute("processing.timestamp", System.currentTimeMillis()))
  Async[F].pure(wikiEdit)
}
```

**Traced:**
- ✓ Event attributes (title, user, bot, server)
- ✓ Processing timestamp
- ✓ Span creation overhead (~microseconds)

**Not Traced:**
- ✗ Downstream processing time
- ✗ JSON parsing (happens before middleware)
- ✗ Topic publishing (happens after middleware)

---

## Quick Reference: When Does the Span End?

| Code Pattern | Span Closes When |
|-------------|------------------|
| `.use { span => F.pure(x) }` | Immediately (microseconds) |
| `.use { span => doWork() }` | After `doWork()` completes |
| `.use { span => for { a <- work1; b <- work2 } yield b }` | After entire for-comprehension |
| `.surround { work() }` | After `work()` completes |
| `.allocated` | You must call the cleanup function manually |

---

## Debugging: Adding Span Events

You can add events to see the timeline within a span:

```scala
.use { span =>
  for {
    _ <- span.addEvent("validation_started")
    validated <- validate(wikiEdit)
    _ <- span.addEvent("validation_completed")
    _ <- span.addEvent("enrichment_started")
    enriched <- enrich(validated)
    _ <- span.addEvent("enrichment_completed")
  } yield enriched
}
```

This creates a timeline view in your tracing UI showing exactly when each step occurred.

---

## Conclusion

**Your current code is correct** - the span ends automatically when the `.use { }` block completes.

**Key Takeaways:**
1. `.use { }` guarantees the span closes (no manual cleanup needed)
2. Span duration = time spent inside `.use { }` block
3. For pipeline tracing, use nested spans in each processing stage
4. The `span` object itself closes automatically - you don't call `.end()`

**Next Steps:**
- Keep current implementation if you only need event receipt tracking
- Add processing logic inside `.use { }` if you want to measure that work
- Create separate span-aware pipes for downstream stages

The beauty of `.use` is that you **never** have to worry about forgetting to close spans - the resource management handles it for you! 🎉


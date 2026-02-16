# Quick Reference: Where Spans Start and End

## Your Current Implementation

### StreamTracingMiddleware

**Location:** Middleware applied in `WikiStream.start`

```scala
tracer
  .spanBuilder("process_wiki_edit")
  .build
  .surround {
    Async[F].pure(wikiEdit)  // ← Span ENDS here (immediately)
  }
```

**Timeline:**
- Span STARTS: When event enters middleware
- Span ENDS: When `pure(wikiEdit)` returns (~microseconds)
- **Duration captured:** Just the span creation overhead

---

### WikiEventLogger

**Location:** In `subscribeAndLog` subscription

```scala
tracer
  .spanBuilder("log_wiki_edit")
  .build
  .use { span =>
    for {
      currentTime <- Async[F].monotonic
      count <- counterRef.updateAndGet(_ + 1)
      elapsedTime = currentTime - startTime
      _ <- span.addAttribute(Attribute("log.count", count.toLong))
      _ <- span.addAttribute(Attribute("log.elapsed_seconds", elapsedTime.toSeconds))
      _ <- Async[F].delay(formatOutput(count, elapsedTime, event))
    } yield ()
    // ← Span ENDS here (after all logging completes)
  }
```

**Timeline:**
- Span STARTS: When event is received from Topic
- Span ENDS: After logging completes (~milliseconds)
- **Duration captured:** Time to get monotonic time, update counter, add attributes, and print

---

## The Answer to "Where Do I End the Span?"

### You Don't! 

The `.use { }` method **automatically ends the span** when the block completes.

```scala
.use { span =>
  // Span is OPEN here
  doWork()
  // Span is STILL OPEN here
  moreWork()
  // Span CLOSES automatically when this block finishes
}
// Span is CLOSED here
```

### What `.use` Does

```scala
// Conceptually, .use is like this:
def use[A](f: Span[F] => F[A]): F[A] = {
  val span = startSpan()        // 1. Create and start span
  try {
    f(span)                      // 2. Run your code
  } finally {
    span.end()                   // 3. Always end span (even on error)
  }
}
```

---

## Visualization

### Current Flow

```
┌─────────────────────────────────────────────────────────────┐
│ StreamTracingMiddleware                                      │
│                                                              │
│   tracer.spanBuilder("process_wiki_edit").build.surround {  │
│     ┌────────────────────────────────────────┐             │
│     │ SPAN "process_wiki_edit" ACTIVE        │             │
│     │                                        │             │
│     │   Async[F].pure(wikiEdit)             │             │
│     │                                        │             │
│     └────────────────────────────────────────┘             │
│   } ← Span ends here                                        │
│                                                              │
│   Returns: WikiEdit                                         │
└─────────────────────────────────────────────────────────────┘
                    ↓
                    ↓ Published to Topic
                    ↓
┌─────────────────────────────────────────────────────────────┐
│ WikiEventLogger                                              │
│                                                              │
│   tracer.spanBuilder("log_wiki_edit").build.use { span =>   │
│     ┌────────────────────────────────────────────────┐     │
│     │ SPAN "log_wiki_edit" ACTIVE                    │     │
│     │                                                │     │
│     │   Get current time                             │     │
│     │   Update counter                               │     │
│     │   Add attributes to span                       │     │
│     │   Format and print output                      │     │
│     │                                                │     │
│     └────────────────────────────────────────────────┘     │
│   } ← Span ends here                                        │
│                                                              │
└─────────────────────────────────────────────────────────────┘
```

---

## If You Want Longer Spans

### Move Work Inside `.use { }`

**Instead of:**
```scala
tracer.spanBuilder("work").build.use { span =>
  Async[F].pure(data)
}
// Span ended, but work happens later
.flatMap(data => doExpensiveWork(data))
```

**Do this:**
```scala
tracer.spanBuilder("work").build.use { span =>
  for {
    data <- Async[F].pure(data)
    result <- doExpensiveWork(data)  // Now inside span!
  } yield result
}
// Span ended after all work
```

---

## Common Patterns

### Pattern 1: Short Span (Event Marker)

```scala
.use { span =>
  Async[F].pure(event)
}
```
- Span duration: ~microseconds
- Use for: Marking that an event occurred

### Pattern 2: Span Covers Processing

```scala
.use { span =>
  for {
    validated <- validate(event)
    enriched <- enrich(validated)
    _ <- store(enriched)
  } yield enriched
}
```
- Span duration: Time for all processing
- Use for: Measuring actual work

### Pattern 3: Nested Spans (Detailed Breakdown)

```scala
.use { parentSpan =>
  for {
    validated <- tracer.spanBuilder("validate").build.use { _ =>
      validate(event)
    }
    enriched <- tracer.spanBuilder("enrich").build.use { _ =>
      enrich(validated)
    }
    _ <- tracer.spanBuilder("store").build.use { _ =>
      store(enriched)
    }
  } yield enriched
}
```
- Creates: 1 parent + 3 child spans
- Use for: Detailed timing breakdown

---

## Summary

**Key Takeaway:** With `.use { }`, you **never manually end spans**. The resource management is automatic.

**Your Current Setup:**
- ✅ `StreamTracingMiddleware`: Span ends immediately (marks ingestion)
- ✅ `WikiEventLogger`: Span ends after logging completes

**To Extend Tracing:**
- Add more `.use { }` blocks in other processing stages
- Each will automatically end its span when done
- OpenTelemetry links parent-child relationships for you

**Rule of Thumb:**
```
Whatever work you want timed = put it inside .use { }
```


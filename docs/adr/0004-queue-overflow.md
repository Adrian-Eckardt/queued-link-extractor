# ADR 0004 — Evict the oldest waiting page on request

**Status** Accepted

## Context

When parsing or output is slow, fetched pages accumulate. Blocking producers preserves complete results. Some callers prefer to retain fresher pages and accept losing the hyperlinks from an older page. Either policy must account for each input occurrence.

## Decision

Use blocking backpressure by default. In `drop-oldest` mode, a new page arriving at a full queue replaces the oldest waiting page with a lightweight dropped outcome and takes the freed slot at the back of the queue. The queue makes that replacement and admission under the same lock. Oldest refers to handoff order. The page currently being parsed, fetch errors, and the end marker are ineligible for eviction.

Keep at most 16 normal entries and 16 pending drop notices. Once the notice allowance fills, or the full queue contains no eligible page, wait for the consumer. The single consumer emits notices in their original queue positions. It receives the end marker only after all input handoffs.

## Consequences

The CLI still produces one indexed outcome per occurrence in a completed batch. A dropped result releases its markup immediately and records the URL and index. Even with blocked output, reporting drops uses bounded memory. A blocked writer can therefore slow producers in either mode.

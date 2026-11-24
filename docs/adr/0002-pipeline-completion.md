# ADR 0002 — Signal completion through the page queue

**Status** Accepted. [ADR 0003](0003-bounded-fetch-concurrency.md) extends the handoff protocol to multiple fetch workers.

## Context

A slow final fetch can leave the queue temporarily empty. Exiting at that point loses a page. Waiting indefinitely for another page hangs the command after the final result.

## Decision

Run one producer and one consumer as independently scheduled tasks. The producer places each fetched page into a bounded FIFO queue with its original input index and URL. After its final handoff, the producer appends an end marker. The consumer waits for messages and exits upon reaching the marker. A full queue makes the producer wait for space.

The coordinator observes both tasks in completion order. A failure that prevents either stage from continuing, such as a broken output stream, interrupts its peer. The coordinator waits for the workers to terminate before returning. If both workers fail independently, the first failure retains the second as a suppressed cause. An interrupt caused by shutting down the peer is expected.

## Consequences

The same protocol works for an empty input and a full queue. A latch-controlled test holds the final fetch open until the first result is visible. With parallel downloads, the worker that completes the final handoff appends the marker.

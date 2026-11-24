# ADR 0003 — Bound concurrent fetches before submission

**Status** Accepted

## Context

A slow server delays independent inputs when the producer downloads one page at a time. The consumer already handles pages independently using their original input indices. A large input list must not create a waiting virtual thread for every URL.

## Decision

Use a virtual thread for each admitted download. Keep a window of at most four outstanding fetch tasks by default and allow callers to choose a window of 1 through 64. Reject larger values before starting any downloads, so a slow consumer cannot accumulate thousands of in-flight pages. A slot becomes available after its page or error enters the bounded queue. Submit the next input when a slot becomes available, even if an earlier fetch is still running.

A single consumer writes outcomes in queue order. With parallel downloads this order can differ from the arguments. After each handoff, its worker decrements the count of inputs still awaiting handoff. Only the worker that reaches zero sends the end marker. A failed worker or an interrupted coordinator stops the run and interrupts active peers.

## Consequences

The input index stays stable for duplicates and for out-of-order completions. A slow consumer applies backpressure to fetchers through the same bounded queue. A window of one preserves sequential fetching. This decision extends the single-producer completion protocol in [ADR 0002](0002-pipeline-completion.md).

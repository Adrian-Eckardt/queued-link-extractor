# ADR 0001 — Keep the extraction pipeline in one Java module

**Status** Accepted for the initial design

## Context

The CLI must fetch pages, hand their markup to a concurrent consumer, and associate each result with its input. To test shutdown reliably, a test needs to hold a fetch open and observe another page's output. Real network timing makes that test unreliable.

## Decision

Use Java 25 LTS and a single Maven module with ports and adapters. The CLI will translate command-line arguments into inputs and wire the application together. The application will own queue capacity, lifecycle, and per-input outcomes. A fetch operation will return the markup and effective URI after redirects. A parsing operation will extract links, and a result writer will emit outcomes. Use ordinary constructors to supply these collaborators.

The first implementation will use the JDK HTTP client and jsoup at the edges. A pipeline test can supply a fetcher controlled by a latch and a writer that captures individual outcomes. An integration test can drive the real HTTP and HTML adapters through a local server.

## Consequences

We can exercise the real queue and completion rules with deterministic inputs. One module keeps the build small. Package dependencies and code review will enforce the boundary. Separate Maven modules would give compile-time isolation but add build structure before there is a second application or reusable library. Decisions about scheduling, overflow, and retries belong with the milestones that introduce those behaviors.

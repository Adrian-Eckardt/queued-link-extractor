# ADR 0005 — Bound retries and share cooldowns by origin

**Status** Accepted

## Context

A 429 response asks the client to reduce its request rate. Retrying immediately increases pressure on the server. A 401 or 403 response requires a change in access rights. Repeating the same anonymous GET cannot supply those rights.

## Decision

Give each input at most one retry after a 429 and five seconds of total cooldown waiting, within its existing twenty-second download deadline. Read Retry-After as seconds or an HTTP date. Use a one-second fallback for a missing or unusable value. When a server asks for a longer delay, report a fetch error for that input and retain the full delay for subsequent requests to the same origin.

Share cooldowns by scheme, host, and effective port. Concurrent 429 responses can extend the cooldown. Convert an HTTP date to a delay when the response arrives and measure elapsed time with a monotonic clock. Recheck after waking. After an origin returns 429, admit at most one request every 100 ms to that origin for the rest of the batch. Admission is atomic across workers. Observe the policy before each GET, including redirect hops. Follow up to five normal HTTP redirects and preserve HTTPS downgrade protection. Report 401, 403, other HTTP errors, and transport failures as terminal outcomes for the input.

## Consequences

A waiting input keeps its bounded fetch slot. Other admitted origins can continue while it waits. A fully occupied window delays new admissions until a slot frees. The wait budget bounds that cost and keeps sequential mode in input order. Already admitted requests may complete after another worker observes a 429.

The clock and wait operation are supplied to the cooldown policy so tests can verify timing without real retry delays.

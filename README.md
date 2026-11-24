# Queued Link Extractor

List the hyperlinks from each supplied web page as JSON Lines. Up to four fetch tasks download independent pages concurrently while one consumer parses the HTML and writes each result. Only the supplied pages and their redirects are fetched.

## Build and run

Install JDK 25 and point `JAVA_HOME` at it. The Gradle Wrapper downloads Gradle and the dependencies on the first build. From the repository root, run

```sh
./gradlew clean build installDist
build/install/queued-link-extractor/bin/queued-link-extractor https://example.org/
```

Set the number of outstanding fetches with `--concurrency N` before the URLs. The supported range is 1 through 64. Use `--concurrency 1` for sequential downloads and input-order output.

On Windows, run `gradlew.bat clean build installDist` and use the `queued-link-extractor.bat` launcher under `build\install\queued-link-extractor\bin`.

## Try two inputs locally

In one terminal, create a page and serve it with the JDK's web server.

```sh
pages=$(mktemp -d)
echo '<a href="guide">Guide</a>' > "$pages/index.html"
jwebserver -b 127.0.0.1 -p 8000 -d "$pages"
```

In another terminal, run the installed command from the repository root.

```sh
build/install/queued-link-extractor/bin/queued-link-extractor \
  --concurrency 1 http://127.0.0.1:8000/index.html http://127.0.0.1:8000/missing
```

Expected standard output

```jsonl
{"input":1,"url":"http://127.0.0.1:8000/index.html","status":"ok","links":["http://127.0.0.1:8000/guide"]}
{"input":2,"url":"http://127.0.0.1:8000/missing","status":"error","stage":"fetch","error":"HTTP 404"}
```

Stop the web server with Ctrl+C.

## Upgrading from sequential fetching

Earlier versions emitted one line per argument in input order. The default now allows four downloads. Scripts should match each result to its argument using the one-based `input` field, or pass `--concurrency 1` to preserve positional order.

## Input and output contract

Supply HTTP or HTTPS URLs as command-line arguments. Each occurrence receives its own one-based input index, including repeated URLs. Results appear in queue arrival order, so a later input can finish before an earlier one. For each input, the command emits and flushes one JSON object on standard output. A successful result contains the resolved targets of `a[href]` elements in document order, including duplicates. Relative links use the effective URI after redirects and honor an HTML base element. Targets that jsoup cannot resolve are omitted. Resolvable schemes such as `mailto:`, `tel:`, `data:`, and `javascript:` are included as text. A page with zero links produces an empty list.

Recoverable fetch and parse errors identify the input, stage, and reason. A completed batch exits with status zero, including batches with per-input errors. A failure that prevents the run from completing, such as a broken output stream, produces a nonzero exit. Fatal JVM errors terminate the batch. Running without arguments prints usage on standard error and exits with status zero. Invalid concurrency values or missing arguments after the option produce exit status 2.

The queue holds up to 16 waiting pages. Each fetch holds its slot until its handoff succeeds. When the queue fills, the workers wait for the consumer to make room. The fetcher allows five seconds to connect and twenty seconds for a complete download, including the response body. It accepts up to 2 MiB of HTML or XHTML per page and reports missing or unsupported media types as fetch errors. HTTP 204 and 205 produce empty results. The HTTP charset takes precedence over an HTML charset declaration. Unfollowed redirects identify their status and destination when supplied.

## Verification

`./gradlew clean build` runs both JUnit suites and checks for zero missed production lines, branches, and bytecode instructions with JaCoCo. Run `./gradlew test` for unit tests or `./gradlew integrationTest` for the CLI tests against a loopback HTTP server. Open `build/reports/jacoco/test/html/index.html` for the combined coverage report. The GitHub Actions **Verify** workflow runs the clean build on Linux and saves the coverage and test reports as downloadable artifacts.

| Behavior | Executable example |
| --- | --- |
| Independent downloads overlap within a limit | [FetchConcurrencyIT](src/integrationTest/java/dev/linkextractor/FetchConcurrencyIT.java) holds real HTTP responses open and measures peak overlap with both the default and an explicit limit. |
| A slow final fetch leaves the queue temporarily empty | [PipelineTest](src/test/java/dev/linkextractor/PipelineTest.java) holds the final fetch open, observes the first output, then releases the fetch and checks both results. |
| Fetch or parse fails in the middle of a batch | The same suite checks every outcome, including two occurrences of the same good URL. It also checks worker shutdown after an output failure. |
| A page contains relative links, duplicates, or no anchors | [HtmlLinksTest](src/test/java/dev/linkextractor/HtmlLinksTest.java) checks the extracted list and HTML base handling. |
| A redirect changes the base URI and another page returns 404 | [LinkExtractorIT](src/integrationTest/java/dev/linkextractor/LinkExtractorIT.java) checks the JSON Lines emitted by the CLI. |

### Planned CI examples

Milestone 3 adds a commented SonarQube example to the [verification workflow](.github/workflows/verify.yml). Further examples are planned alongside their milestones.

| Milestone | What the workflow comments will illustrate |
| --- | --- |
| 3 · Concurrent fetching | Where SonarQube analysis would run after Gradle and import the JaCoCo XML report to show coverage alongside code findings. |
| 5 · HTTP resilience | Where a Mend software composition analysis scan would inspect the resolved Gradle dependencies and surface vulnerable components for review. |
| 6 · Delivery | An optional deployment example showing how a successful build's CLI distribution could be promoted to a staging environment. |

These examples will appear only as YAML comments. The coverage report from the **Verify** job remains available for reviewers to inspect directly.

## Design and development

[ADR 0001](docs/adr/0001-application-boundaries.md) describes the application boundaries. [ADR 0002](docs/adr/0002-pipeline-completion.md) explains queue completion and worker shutdown. [ADR 0003](docs/adr/0003-bounded-fetch-concurrency.md) records how the fetch window and final handoff work. Later milestones cover optional oldest-page eviction and HTTP retry and rate-limit handling.

AI assisted with drafting the implementation and tests. During development, the overlap test failed against an initial fetch-then-parse implementation. Running the producer and consumer independently made the first page visible while the second fetch was still blocked. The commit introducing the queue includes that test and the associated decision.

# Queued Link Extractor

A command-line tool for listing the hyperlinks found on each supplied web page. This first milestone defines the behavior and sets up the Java build. The executable comes in the next milestone.

## Verify the build

Install JDK 25 and point `JAVA_HOME` at it. From the repository root, run the Maven Wrapper. The first run downloads Maven and the build dependencies.

```sh
./mvnw --version
./mvnw clean verify
```

On Windows, use `mvnw.cmd clean verify`. The GitHub Actions **Verify** workflow runs the same clean build on Linux.

Unit tests will use JUnit Jupiter with Surefire (`*Test`), and integration tests will use Failsafe (`*IT`). JaCoCo combines both test runs and fails verification on any missed production line, branch, or bytecode instruction. Once production sources exist, the build also requires unit tests. This milestone has no production classes, so coverage is currently unmeasured. With classes and tests present, open `target/site/jacoco/index.html` locally or download the coverage report from the workflow run.

## Input and output contract

Supply one or more HTTP or HTTPS URLs as command-line arguments. Each occurrence is a separate input. The planned output is one JSON object per line on standard output, with the original URL and a one-based input index. A successful outcome includes a list of hyperlinks in document order. A page with zero links produces an empty list. Results can arrive in completion order. Only supplied pages are fetched.

For the input `https://example.org/docs/index.html` containing `<a href="guide">Guide</a>`, the expected output is:

```json
{"input":1,"url":"https://example.org/docs/index.html","status":"ok","links":["https://example.org/docs/guide"]}
```

## Acceptance examples

| Given | When | Then |
| --- | --- | --- |
| A ready page and a second fetch still in flight | The consumer drains the queue | It emits the first result, waits for the second, and exits after emitting both. |
| Two valid pages and one failed fetch | The failed fetch returns HTTP 404 | The failed input has a fetch error, and both valid pages have their own results. |
| Two pages, one of which fails during parsing | The consumer encounters the parse failure | It records the error against that input and continues with the other page. |
| A redirect to a page containing relative links | The HTML is parsed | Links resolve against the effective document base. The result retains the original input URL. |
| Three ready pages and space for two waiting pages | The consumer pauses | The default mode waits for space. In drop-oldest mode the earliest waiting page gets a dropped outcome. |
| A server responds with HTTP 429 | The permitted wait and retry budget allow another attempt | The fetcher observes the origin cooldown before retrying. HTTP 401 and 403 are terminal for that input. |

## Design and development

[ADR 0001](docs/adr/0001-application-boundaries.md) records the application boundaries. The implementation sequence is the basic producer and consumer, bounded concurrent fetches, queue overflow handling, and HTTP retry behavior. Each step will add executable examples and record significant design decisions alongside the code.

AI assisted with identifying acceptance examples, drafting the build configuration, and checking its behavior. The commit history records the progression of the work.

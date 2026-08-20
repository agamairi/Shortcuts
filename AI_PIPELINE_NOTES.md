# AI pipeline notes — grounding and multi-call harness

## Validated in code and JVM tests

- `GroundingContext` enumerates only `ACTION_MAIN` + `CATEGORY_LAUNCHER` activities through
  the existing scoped manifest query. It does not request `QUERY_ALL_PACKAGES`.
- App matching normalizes case, whitespace, and punctuation; it uses exact, prefix,
  token-subset, and bounded edit-distance tiers, and returns `null` for ambiguous or unmatched
  input.
- Function-call parsing collects every valid supported call in response order and ignores a
  malformed call without discarding later valid calls.
- The prompt's app-list injection is measured with `LlmInferenceSession.sizeInTokens`; it is
  capped below the 1024-token context window while reserving output tokens.

## Unvalidated assumptions / device follow-up

- Tier 1's real-world multi-call hit rate is **UNVALIDATED**. FunctionGemma is documented as
  normally producing one call per generation, and the model file was not available in this
  environment. Test the full-prompt batch attempt on a physical device and measure how often it
  emits two or more valid calls.
- Tier 2 remains the deterministic safety net: when the batch yields fewer calls than the
  quote-aware `PromptSegmenter` clauses, it generates only uncovered clauses and preserves an
  `Unresolved` draft step on failure.

## Build verification

`./gradlew assembleDebug testDebugUnitTest` could **not** run in this sandbox. Gradle's required
single-use daemon failed before compilation with `java.net.SocketException: Operation not
permitted` while binding its local TCP socket. Retrying via the installed Gradle 8.2 binary,
temporary writable `GRADLE_USER_HOME`, `--no-daemon`, and blank `org.gradle.jvmargs` failed at the
same sandbox restriction. Consequently, the changed Kotlin files have not been compile-checked
here; they require local/CI verification.

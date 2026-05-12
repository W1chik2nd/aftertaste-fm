# Agent Constraints

This repo will rot into a shit mountain if every change adds fallback paths, magic constants, dead env flags, and stringified contracts that the next change has to work around. The rules below are **not aspirational**. If you cannot follow one, stop and ask the maintainer. Do not silently route around a rule with a "small exception just this once."

Read this file before opening any source file. The product rule and architecture boundaries are immutable. The hard rules cite specific past mistakes — they exist because the codebase has already eaten them once.

---

## Product rule (immutable)

The host speaks **between groups of songs**, not before every song. The first track in a segment is the chapter lead; the host can speak over its opening, and the remaining tracks play clean.

Queue shape:

```
HostVoiceItem(with lead Track)
TrackItem
TrackItem
HostVoiceItem(with lead Track)
TrackItem
TrackItem
```

Default host configuration:

```json
{
  "hostLanguage": "en-US",
  "hostStyle": "calm late-night radio",
  "hostName": "Aftertaste",
  "segmentSpeechMode": "between_segments"
}
```

If a change makes the host talk before every track, or breaks the queue shape, it is wrong. Revert.

---

## Architecture boundaries (immutable)

- **React talks to radio-server only.** The web app does not call the netease-adapter, or any provider, directly. Provider statuses are aggregated through `/api/health/...` routes on radio-server.
- **radio-server owns** planning, queue state, host scripts, host language, taste profile reads, weather context, persistence, intent routing.
- **Provider-specific shapes live only in `apps/<provider>-adapter/` and the matching `*MusicProvider.kt`.** Cookies, `vip_required`, `freeTrialInfo`, region codes never appear in `Models.kt`, `RadioEngine.kt`, or web code.
- **`MusicProvider` stays generic.** Add a method only when at least two providers will implement it. A one-off capability is exposed through that provider's class, not the interface.
- **Mock-first is the contract.** The whole product runs end-to-end with `MUSIC_PROVIDER=mock` and zero credentials. Any change that makes credentials required for local dev is wrong.

---

## Hard rules

### 1. Fallbacks

Excessive fallback paths are the primary way this codebase rots. Apply these rules strictly.

- **Do not add a fallback path until the primary has actually failed in real use, and you can name the failure.** "Just in case" fallbacks are banned. If you think "what if X is null?" — first check whether X can actually be null in this call site.
- **One mock fallback per concern.** `MockMusicProvider` is the fallback for missing/broken music providers. Do not add another tier of fallback inside the provider, inside the engine, and inside the planner.
- **Do not pad LLM output with arbitrary fallback values.** If the model returns a segment with too few tracks, drop the segment. Do not fill the gap with the next candidate just to keep the count at 3.
- **The deterministic `ShowPlanner` is the no-LLM-key path, not a backup for "LLM call failed once."** If a configured LLM call fails, surface the error and let the request fail. Do not silently fall through to canned host scripts.
- **Legacy migration code is temporary.** Keep it for at most one release after the schema change, then delete it. Do not let `importLegacyState`-style stubs accumulate.
- **`runCatching { ... }.getOrNull()` is banned as a silent swallow.** Either log via SLF4J at `warn` or higher, or use `getOrElse { typed_alternative }`. Same rule for TypeScript: no `.catch(() => undefined)` without a logged reason.
- **Catch the specific exception you can recover from.** Don't catch `Throwable` / `unknown` / `Exception` to "be safe."

### 2. Code volume

- **No source file exceeds 300 lines.** If your change would push one over, split first, change second.
- **No function exceeds 60 lines.** Kotlin DSL builders (`buildJsonObject`), JSX trees, and Express handler chains count.
- **A new 100-line file with one caller is worse than 30 added lines in an existing file.** Inline first; extract on the second caller.
- **No class with more than five distinct responsibilities.** If the class touches HTTP, files, business logic, parsing, and config, split.

### 3. Magic values

- **Named constants for numeric limits.** Chunk size, segment count, candidate cap, truncation length, retry counts. Bare `3`, `4`, `80`, `900` in business logic is banned.
- **Silent `take(N)` truncation is banned.** Either name N as a constant with a comment explaining the budget, or raise the limit, or fail loud. The codebase has already shipped LLM outputs cut mid-word from `take(900)`; do not repeat.
- **No hardcoded UI copy in deterministic planners.** Chapter titles, host scripts, mood labels live in config, come from the LLM, or — preferably — get removed. Strings like `"Chapter One - Opening the Room"` in Kotlin are exactly the kind of thing that should not exist.

### 4. Routing and intent

- **`IntentExtractor` is the ONLY place in the codebase that does substring matching on user prompts.** Not "the main place" — the only place. Including normalization, alias detection, and language guessing.
- **`explicitCommandDecision` is the ONLY place that maps prompts to deterministic playback commands** (`next`, `pause`, etc.). Keep its keyword list tight; "what's playing" / "skip" / "继续" are fine. Anything fuzzy goes through the LLM.
- **Sentiment, retune complaints, mixed-language phrasing, "less sad", "make it warmer", "more like X but with Y"** go through `AgentChatService` (LLM router with structured JSON output), not heuristics.
- **When `IntentExtractor` grows past ~5 categories, replace it with an LLM router.** Do not keep adding branches.
- **Do not add substring matching in React.** "If user typed 'skip', call /next" is fine. "If user typed 'less english', shape recommendation" belongs on the server, behind the LLM agent.

### 5. Data flow

- **Structured data stays structured.** Do not stringify a typed field into `recentSignals` and parse it back downstream. If `RoutingIntent.language == "en"` exists, read it; don't write `"language-hint=en"` then `signal.startsWith("language-hint=")` somewhere else.
- **`AgentTrace` describes what actually happened in this run.** Do not hardcode `"RadioAgent -> TasteProfileRepository -> CandidateSelector"` if the run skipped one of those steps. Lying in debug surfaces is worse than not having them.
- **API contracts on both sides match.** If radio-server returns full `ShowPlan` with `segments` and `hostConfig`, the web `types.ts` mirrors it exactly. "The UI doesn't use it so I'll leave it `unknown`" hides backend changes.

### 6. Resources

- **One shared `HttpClients.shared`.** Do not `HttpClient(CIO) { ... }` inside a service. Services accept `HttpClient` via constructor with `HttpClients.shared` as default.
- **One `StateStore` mutex, one `RadioEngine` mutex.** Do not add a third lock layer for "extra safety."
- **Atomic file writes.** Anywhere we write user-visible files (TTS cache, taste drafts, imports): write to `.part`, then `Files.move` (atomic where supported, fallback non-atomic). No partial files visible to readers.
- **No suspend function silently does blocking I/O.** Wrap with `withContext(Dispatchers.IO)`. Especially: JDBC, `java.net.http.HttpClient.send`, file `write` on large payloads.

### 7. Errors and config

- **Fail loud at startup, not at request time.** If `LLM_API_KEY` is set but malformed, refuse to start. If a required directory is missing, create it or refuse to start.
- **Read each env var in one place.** Don't sprinkle `?.toIntOrNull() ?: default` checks at every read site. Read once, validate once, hold the typed value.
- **Surface real error bodies.** API errors on both sides include the upstream response body snippet (truncated to ~240 chars), not just `500 Internal Server Error`.
- **No `null` overloading.** Don't use `null` to mean both "no data" and "something went wrong." If both are real states, use a sealed result type or distinct sentinel.

### 8. Speculation

- **No env knob until someone needs it.** A flag no one sets and no one tunes is dead weight that future agents have to special-case.
- **No interface with one implementation** unless that implementation is a mock provider for the explicit "run without credentials" path.
- **No abstract base class anticipating future variants.** Refactor when the second variant lands.
- **No "TODO: support X" code paths.** Either implement X now, or don't write the stub.
- **No new abstraction with under three real callers.** Three similar lines is fine. The wrong abstraction is harder to remove than a bit of duplication.

### 9. Boundaries between user and provider

- **Do not hardcode this user's artists, playlists, or aliases in runtime code.** User-specific data goes in private `data/taste/rules.json` under `artistAliases`. The runtime reads from there.
- **The offline analyzer taxonomy stays general.** Do not specialize `scripts/analyze-playlist-openai.mjs` for one playlist's genres. The taxonomy must keep making sense across languages, providers, and listening contexts.
- **Do not commit cookies, downloaded audio, generated TTS files, or `data/taste/` contents.**

---

## Taste data flow

- Real private data lives in `data/taste/`, which is gitignored.
- Committed examples live in `data/taste.example/`.
- Prefer `data/taste/tracks.evidence.json` when it exists; it carries confidence and evidence per tag/score.
- Playlist import writes raw metadata, a tagged draft, and a lyrics cache. The import endpoint does **not** call the runtime LLM. Offline analysis (`scripts/analyze-playlist-openai.mjs`, `scripts/build-evidence-analysis.mjs`) produces `tracks.evidence.json`.
- Runtime flow: `TasteProfileRepository` → `CandidateSelector` → `LlmShowPlanner` (or deterministic `ShowPlanner` when no key is set).
- The runtime LLM call sees a small candidate pool plus compact evidence — **never** the full listening history.
- `profile.md` and `rules.json` are regenerated from `tracks.evidence.json` via `scripts/build-taste-profile.mjs`. Do not hand-edit them mid-pipeline.

---

## Anti-patterns already shipped once (do not redo)

Concrete mistakes from earlier iterations. They are listed so the next change does not introduce them again.

- **Four parallel `HttpClient` instances**, one per service. Use `HttpClients.shared`.
- **Hardcoded `agentTrace.routing` strings** that lied about which components ran.
- **Padding LLM segments with fallback ids** to force `size == 3`. If the model didn't fill the segment, drop it.
- **`agentChat.fallbackDecision` growing a keyword list** that duplicated the LLM router. The fallback is for explicit commands only.
- **`HostConfig.hostStyle` stored in env as kebab-case** and patched in code with `.replace("-", " ")`. Env values are the user-facing form; code does not rescue mismatched defaults except as a one-line backward-compat shim.
- **`recentSignals: List<String>`** carrying typed routing info as parseable strings. Use `RoutingIntent` fields.
- **`prefs` table written but never read.** If a table or column has no live reader, delete the writer.
- **Legacy `state.json` migration code** kept long after migration was done.
- **`System.err.println` in production code** while logback was already configured. Use SLF4J.
- **`Path.of("../../data/...")` relative paths** that broke when cwd shifted. Use `Env.path(name, default)`.
- **`runCatching { ... }.getOrNull()` chains** that swallowed every error class equally and left no trace. Log at warn or use typed alternative.
- **`webSocket("/ws/stream") { while(true) { ... } }`** with no `isActive` check and no close handler. Use `isActive` and let cancellation propagate.
- **Static `take(80)` / `take(400)` / `take(900)` truncations** that cut LLM output mid-word. Either raise the limit or fail loud.
- **`adapter /song/url` returning a single object for one id and an array for many.** API shape must not depend on input cardinality.

---

## Definition of done

A change is done when:

1. It does not violate any rule above.
2. `./gradlew compileKotlin`, web `tsc -b`, and adapter `tsc --noEmit` all pass.
3. The mock-first end-to-end path (no Netease, no LLM key, no Fish key) still runs `npm run dev` cleanly and produces a playable show.
4. Any new env var has a documented default in `.env.example` and is referenced from `docs/api.md` or `docs/architecture.md` exactly once.
5. No new file pushes its directory over the 300-line rule without being split.
6. Debug surfaces (`agentTrace`, logs, error responses) describe what actually happened — not what was supposed to happen.

If you cannot satisfy 2 or 3, the change is not done. Do not merge.

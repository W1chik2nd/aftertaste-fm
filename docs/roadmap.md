# Roadmap

## Done

### v0.1 — Prototype
Monorepo (`services/radio-server`, `apps/web`, `apps/netease-adapter`), mock planning, queue
controls, normalized adapter responses, docs for future agents.

### v0.2 — Library & Import
Netease playlist import (metadata + lyrics, no LLM), `AnalysisJobService` (job model with
progress + cancel), per-track evidence files at `data/taste/tracks/<provider>/<id>.json`,
Library / Import / Settings views.

### v0.3 — Real Voice
Fish TTS through `HostVoiceService`, atomic-write cache at `cache/tts/<hash>.<ext>`, digest-based
skip for identical scripts. Configured in `.env` (key currently commented for debugging).

### v0.4 — LLM Planning
`ConfiguredLlmShowPlanner` + `LlmCompletionClient` supporting OpenAI Responses, chat
completions, and Anthropic. Deterministic `ShowPlanner` retained as the no-LLM-key path
per AGENTS.md §1.

### v0.5 — Chinese host
Host language switchable at runtime from Settings (`POST /api/settings/host-language`,
persisted in `app_state`). `LlmShowPlanner` carries a Mandarin-specific writing directive
with negative ad-copy examples; deterministic planning keeps queue shape without authoring
host copy; `RadioAgent` trace and `AgentChatService` fallback copy follow the language.
Independent Chinese Fish credentials via `FISH_API_KEY_ZH` / `FISH_VOICE_ID_ZH`,
falling back to the default key when unset. Named per-daypart Chinese styles and mixed-language
shows are still open (see *Later*).

### v0.6 — Frontend visual redesign
The React app has moved from the earlier light prototype into the dark terminal-radio surface:
`ClockHero`, self-hosted display/mono fonts, tokenized dark styling, waveform-aware now-playing,
and split Player / Library / Import / Settings views. This counts as the first complete visual
reskin. Future visual work should be treated as iteration on the live UI, not a separate redesign
spec.

---

## Next directions

Three directions on the table. Each has a one-paragraph assessment so the trade-off is
visible before the work starts.

### A. Finish PWA shell

**Why now:** the cheapest path from "web page in a tab" to "feels like an installed app".
Same React codebase, no extra platform, no Apple Developer fee. Wraps everything that's
already shipped.

**Current state:** partial. `vite-plugin-pwa`, manifest metadata, app-shell caching, dark
theme colors, and the update/offline prompt exist. This is not Done yet because the playback
surface is still missing lock-screen / notification metadata, and media caching is deliberately
conservative.

**What remains:**
- Add `navigator.mediaSession` bindings in `usePlayer` — current track metadata + artwork +
  `setActionHandler('nexttrack', …)`. This is what gets you lock-screen controls and the
  Now Playing notification on Android / macOS / Windows.
- Decide whether `/media/tts/*.mp3` should be cached for offline replay, while keeping provider
  streams network-only so stale signed URLs are not replayed.
- Verify install behavior on macOS Chrome / Safari and iOS Safari.

**What this buys vs current:**
- Installable on macOS (Safari, Chrome, Edge) and iOS Safari with a real app icon and no
  browser chrome.
- Lock-screen artwork and prev/next controls on most platforms.
- Offline replay of cached host segments and tracks.

**What it does *not* buy** (the native-app frontier):
- AirPlay routing control, Shortcuts/Focus integration, Widgets, Control Center.
- Reliable iOS background audio — Safari kills backgrounded PWAs aggressively.
- Push notifications on iOS without the user installing the PWA first.

**Effort:** low. **Risk:** iOS Safari's PWA support is the
weakest of the targets; treat iOS as "best-effort", macOS/Android as first-class.

### B. Netease similar-track expansion

**Why now:** this is the first path beyond "recommend only from imported playlists" without
taking on a whole new provider. NeteaseCloudMusicApi already exposes `/simi/song`; the useful
product shape is "more like the current track, but filtered through Aftertaste's taste profile,"
not "let the LLM invent similar songs."

**What to add:**
- `apps/netease-adapter`: expose `/simi/song?id=<trackId>` and normalize the result to `Track[]`.
- `NeteaseMusicProvider`: add a provider-specific `getSimilarTracks(trackId)` method. Do not add
  it to `MusicProvider` until a second provider implements the same capability.
- `radio-server`: add an explicit discovery path for "more like this" that expands candidates
  from the current track, removes current queue / recent plays / imported-library duplicates,
  hydrates stream URLs, and drops unavailable tracks instead of padding.
- Planning: pass the expanded candidate pool through existing routing intent, station style,
  taste evidence, and `LlmShowPlanner` / `ShowPlanner` so the queue shape stays unchanged:
  `HostVoiceItem(with lead Track) + TrackItem + TrackItem`.
- Debug: make `agentTrace` say that a Netease similar-song expansion actually ran, including
  seed track id, candidate count, playable count, and drop reasons at a compact level.

**What this buys vs current:**
- The station can keep moving outward from one strong song instead of staying inside imported
  playlists.
- The AI becomes a curator/reranker over real provider candidates, which is much safer than
  hallucinating song titles.
- The feature remains mock-first by leaving existing recommendation behavior intact when the
  active provider is `mock`.

**What it does not buy:**
- It is not a Spotify-style full taste model. Netease similarity is a provider signal, not a
  complete personal recommender.
- It does not guarantee playback. VIP, region, login, and missing stream URLs still need to be
  surfaced or filtered.

**Effort:** medium. **Risk:** recommendation quality depends on Netease similarity quality and
cookie/playability state; keep the first version scoped to current-track expansion.

### C. More providers

**Architecture is ready.** `MusicProvider` is the generic boundary; per-provider adapters live
under `apps/<provider>-adapter/`. The hard part is per-platform auth and licensing, not code shape.

**Realistic order if pursued:**
- **Local files** (zero auth, smallest scope, immediate depth) — start here.
- **CSV import** of listening history exports from other platforms.
- **Spotify** — Premium account + OAuth + their SDK; non-premium tier streams 30s previews only.
- **Apple Music** — MusicKit JS for web, MusicKit framework for native; region restrictions apply.
- **QQ Music / others without public APIs** — adapter would scrape, fragile.

**Effort:** high per provider. **Risk:** value-per-effort drops fast after the second provider.

---

## Suggested sequence

1. **A — Finish PWA shell.** Small remaining surface: MediaSession, install verification, and a
   deliberate media-cache decision.
2. **B — Netease similar-track expansion.** Biggest jump in perceived recommendation depth
   without changing providers.
3. **C first half — local files + CSV import.** Provider depth without auth burden.
4. **C second half — Spotify / Apple Music.** Last; the auth + licensing tax is real.

Native macOS/iOS is deferred to *Later* below — revisit only when the PWA's limits start
biting (AirPlay, Shortcuts, Widgets, reliable iOS background play).

---

## Later

- **Native macOS / iOS app (SwiftUI).** Pursue if/when the PWA's limits become daily friction:
  AirPlay routing, Shortcuts/Focus integration, Widgets, Control Center, MusicKit access,
  reliable iOS background audio. Free Apple ID is enough for personal macOS use; iPhone
  self-install via AltStore avoids the $99/yr fee at the cost of weekly re-signing. The
  $99 buys 1-year sign + TestFlight.
- Manual override / re-tag in the Library UI (deferred from the v0.2 planning doc).
- Mixed-language shows (zh + en in one segment).
- WebSocket realtime UI (`/ws/stream` exists on the server; client wire-up still pending).
- Multi-device handoff.

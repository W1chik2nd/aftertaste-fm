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
with negative ad-copy examples; the deterministic planner uses `HostScriptTemplates` with a
separate Chinese template set; `RadioAgent` trace and `AgentChatService` fallback copy follow
the language. Independent Chinese Fish credentials via `FISH_API_KEY_ZH` / `FISH_VOICE_ID_ZH`,
falling back to the default key when unset. Named per-daypart Chinese styles and mixed-language
shows are still open (see *Later*).

---

## Next directions

Three directions on the table. Each has a one-paragraph assessment so the trade-off is
visible before the work starts.

### B. PWA shell

**Why now:** the cheapest path from "web page in a tab" to "feels like an installed app".
Same React codebase, no extra platform, no Apple Developer fee. Wraps everything that's
already shipped.

**What to add:**
- `apps/web/public/manifest.webmanifest` — name, icons, theme color, `display: standalone`.
- Service worker (probably via `vite-plugin-pwa`) — caches static assets and `/media/tts/*.mp3`
  for offline replay; lets the install prompt fire.
- `navigator.mediaSession` bindings in `usePlayer` — current track metadata + artwork +
  `setActionHandler('nexttrack', …)`. This is what gets you lock-screen controls and the
  Now Playing notification on Android / macOS / Windows.
- `<link rel="manifest">` and theme-color meta in `index.html`.

**What this buys vs current:**
- Installable on macOS (Safari, Chrome, Edge) and iOS Safari with a real app icon and no
  browser chrome.
- Lock-screen artwork and prev/next controls on most platforms.
- Offline replay of cached host segments and tracks.

**What it does *not* buy** (the native-app frontier):
- AirPlay routing control, Shortcuts/Focus integration, Widgets, Control Center.
- Reliable iOS background audio — Safari kills backgrounded PWAs aggressively.
- Push notifications on iOS without the user installing the PWA first.

**Effort:** low (1–2 days for a functional v1). **Risk:** iOS Safari's PWA support is the
weakest of the targets; treat iOS as "best-effort", macOS/Android as first-class.

### C. Frontend visual redesign (React)

**Independent of B.** PWA is shell work (manifest, service worker, MediaSession); visual
redesign is in-app work (motion, audio visualization, typography). They share no surface and
can be done in any order. There's no double-work risk the way there was when B was native.

**Concrete prerequisites before touching CSS:**
- Pick visual references (NTS Radio player, Apple Music now-playing, old Rdio, et al.).
  "Futuristic" without references is bikeshed-bait.
- Decide whether to keep the current view-split structure (`PlayerView`, `LibraryView`, …)
  or collapse into a single fluid surface. Components are fine; the question is information
  architecture, not file layout.

**Effort:** medium (mostly judgment + motion work, not architecture). **Risk:** open-ended;
needs an explicit "good enough" line so it doesn't eat the rest of the roadmap.

### D. More providers

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

1. **B — PWA shell.** 1–2 days of work that turns the existing web app into something
   installable, with lock-screen controls and offline replay. Single biggest jump in
   perceived quality for the smallest cost.
2. **C — Frontend visual redesign.** Once B has shipped, the app already feels like an app —
   then the visual work has a clear stage. Time-box this; it's the easiest item to overrun.
3. **D first half — local files + CSV import.** Provider depth without auth burden.
4. **D second half — Spotify / Apple Music.** Last; the auth + licensing tax is real.

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

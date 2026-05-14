# Claude Code Notes

**Before you write code, read `AGENTS.md`.** It is the binding constraint document for any agent working in this repo, not orientation. The product rule, architecture boundaries, and hard rules there override anything you might infer from the source.

If a rule in `AGENTS.md` conflicts with what looks like established style in the code, the rule wins — the code is wrong and should be fixed when you next touch it.

## Mental model

Aftertaste FM is a private AI radio with three runnable services:

- `services/radio-server` — Kotlin + Ktor, port 8080, the brain. Owns planning, queue state, taste reads, weather, persistence, intent routing.
- `apps/web` — React + Vite, port 5173, the player and debug UI. Talks to radio-server only.
- `apps/netease-adapter` — Node + Express, port 8090, a thin normalizing adapter. Talks to upstream Netease APIs or runs in mock mode.

The product rule: the host speaks between groups of songs, not before each song. `ShowSegment` has one `hostScript` and exactly 3 tracks; the first is the chapter lead, the host can speak over its opening, then the rest plays clean. See `AGENTS.md` for the queue shape and host config defaults.

## Where things live

- Kotlin packages are flat under `fm.aftertaste`. Files split by concern: `RadioEngine`, `PlaybackQueue`, `ShowPlanner`, `RadioAgent`, `HostVoiceService`, `LlmShowPlanner`, `LlmCompletionClient`, `LlmRuntimeConfig`, `AgentChatService`, `IntentExtractor`, `MusicProvider`, `TasteProfileRepository`, `PlaylistImportService`, `WeatherService`, `StateStore`, `HttpClients`, `Models`, `Env`, `Application`.
- Web is split across `App.tsx` (orchestrator), `hooks/{usePlayer,useLyrics,useAudioSpectrum}.ts`, `components/{AgentDock,AgentTracePanel,NowPlayingStage,QueueList,ClockHero,CollapsiblePanel,LyricsPanel,StatusStrip,AppAudio,AppNav}.tsx` plus `components/views/*`, `utils/{format,lyrics,media,network}.ts`.
- Docs: `docs/architecture.md`, `docs/api.md`, `docs/roadmap.md`.
- Private data: `data/taste/` (gitignored). Public schema: `data/taste.example/`.

## Workflow rules specific to Claude Code

- **Don't propose tests when the user hasn't asked.** Tests are deferred for this project; revisit only when the user requests them.
- **Don't change `.env`.** Real keys live there. If you need a new env var, edit `.env.example` and document it in the relevant `docs/*.md`.
- **`gpt-5.2` is a real model in this project's context.** Do not "fix" it to `gpt-4.1-mini` or similar.
- **Use the Plan tool for non-trivial refactors.** A single-file edit is fine to do directly; cross-service changes get a plan first.
- **Use TaskCreate/TaskUpdate for multi-step work.** Mark each task in-progress before working on it and completed as soon as it's done — don't batch.
- **Compile before claiming done.** `./gradlew compileKotlin` (in `services/radio-server`) plus `tsc -b` (in `apps/web`) plus `tsc --noEmit` (in `apps/netease-adapter`). All three must pass.

## Read order before non-trivial changes

1. `AGENTS.md`
2. `docs/architecture.md`
3. `docs/api.md` (for any API change)
4. The specific files you intend to touch

## When in doubt

- A small fix is better than a big abstraction.
- Failing loud is better than a silent fallback.
- Inline duplication is better than a premature interface.
- Asking the maintainer is better than guessing past one of the hard rules in `AGENTS.md`.

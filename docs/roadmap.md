# Roadmap

## v0.1 Prototype

- Monorepo structure.
- Ktor radio-server with mock planning and queue controls.
- React player/debug UI.
- Node netease-adapter with normalized mock responses.
- Documentation for future agents and collaborators.

## v0.2 Personal Taste

- Import Netease playlists and listening history metadata.
- Persist taste profile, show plans, and play history in SQLite.
- Add mood and routine files under a user-owned profile directory.
- Improve recommendation context: time, day, recent skips, and "too much of this lately".

## v0.3 Real Voice

- Add real TTS synthesis.
- Cache generated files at `cache/tts/<hash>.mp3`.
- Avoid regenerating identical host scripts.
- Add voice style presets.

## v0.4 Real Planning

- Replace mock planner with an LLM-backed `ShowPlanner`.
- Keep deterministic JSON contracts.
- Add segment-level guardrails so the host still speaks between song groups.

## v0.5 More Providers

- Local files.
- CSV imports.
- Spotify.
- Apple Music.
- QQ Music.

## Later

- `zh-CN` host support.
- PWA offline shell and service worker.
- Mac/iPhone app experiments.
- WebSocket progress and multi-device handoff.

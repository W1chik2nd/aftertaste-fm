# Agent Orientation

This repository is an early v0.1 prototype for Aftertaste FM, a private AI radio.

Core product rule: the host speaks between groups of songs, not before every song. The current v0.1 behavior treats the first track in a segment as a chapter lead: the host can speak over that lead track's opening, then the remaining tracks play cleanly. Preserve the queue shape:

```text
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

Boundaries:

- React calls only `radio-server`.
- `radio-server` owns planning, queue state, host scripts, and provider selection.
- Netease-specific behavior belongs only in `apps/netease-adapter`.
- Keep `MusicProvider` generic; do not hard-code Netease assumptions into core models.
- Do not commit cookies, downloaded audio, or generated TTS files.

When adding features, prefer mock-first behavior that keeps the whole product runnable without Netease credentials.

Offline taste data:

- Real private data belongs in `data/taste/`, which is gitignored.
- Committed examples live in `data/taste.example/`.
- Prefer `data/taste/tracks.evidence.json` when it exists. It carries confidence and evidence per tag/score.
- Playlist import writes raw metadata, a tagged draft, and a lyrics cache. Do offline analysis before trusting the data.
- The analyzer taxonomy lives in `scripts/analyze-playlist-openai.mjs`. Do not hard-code categories from one playlist into it.
- `profile.md` and `rules.json` are generated from `tracks.evidence.json`; update them through `scripts/build-taste-profile.mjs`.
- Runtime flow should prefer `TasteProfileRepository -> CandidateSelector -> LLM planner`.
- Do not send an entire listening history to the runtime LLM call. Select a small candidate pool first.
- Keep recommendation logic user-agnostic. Do not hard-code Xavier's artists, playlists, tags, or aliases in Kotlin/TypeScript core code. User-specific artist aliases belong in private `data/taste/rules.json` or generated taste data, not in `TasteProfileRepository`, `RadioAgent`, or planner prompts.

Intent and product behavior:

- Build for all users, not just the current developer. Do not hard-code a growing list of guessed words in React or core planning code as a substitute for understanding user intent.
- React may handle only explicit player commands and UI actions. Natural-language interpretation belongs in `radio-server`.
- When users ask why recommendations repeat, ask for variety, compare results, or describe a failure mode, the agent should treat that as tuning/debug context and adjust planning signals or explain behavior instead of relying on fixed keyword buckets.
- Prefer typed intent routing and context assembly over ad hoc substring checks. If a temporary heuristic is needed, keep it server-side, small, documented, and easy to replace with an LLM/router.

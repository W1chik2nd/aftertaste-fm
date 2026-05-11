# Claude Code Notes

Aftertaste FM is a monorepo with three runnable pieces:

- `services/radio-server`: Kotlin/Ktor API on port `8080`.
- `apps/web`: React/Vite player UI on port `5173`.
- `apps/netease-adapter`: Node/Express adapter on port `8090`.

The important mental model is "radio chapter", not "song intro". Each `ShowSegment` has one `hostScript` and roughly 3 tracks. The first track is the chapter lead; the playback queue uses `HostVoiceItem(with lead Track) + TrackItem + TrackItem`, so the host can speak over the lead track's opening and then let the remaining tracks run clean.

v0.1 defaults to English host copy:

- `hostLanguage=en-US`
- `hostName=Aftertaste`
- `hostStyle=calm late-night radio`
- `segmentSpeechMode=between_segments`

Use `docs/architecture.md`, `docs/api.md`, and `docs/roadmap.md` before making broad changes.

The runtime recommendation path now prefers offline tagged taste data:

`data/taste/` -> `TasteProfileRepository` -> `CandidateSelector` -> `LlmShowPlanner` -> `PlaybackQueue`.

`data/taste/` is private and gitignored. Use `data/taste.example/` as the public schema reference.

Do not hard-code this user's artists, playlists, or aliases in runtime code. If a user needs aliases such as a romanized artist name, put them in that user's private/generated `rules.json` under `artistAliases`.

Accuracy note: `tracks.evidence.json` is the preferred private analysis file. It stores confidence and evidence for each tag and score. Do not present weak title/artist guesses as accurate manual analysis.

For offline analysis, keep the taxonomy in `scripts/analyze-playlist-openai.mjs` general across languages, genres, providers, and listening contexts. Regenerate `profile.md` and `rules.json` with `scripts/build-taste-profile.mjs` after changing evidence data.

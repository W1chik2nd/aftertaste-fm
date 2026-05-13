# API

Base URL: `http://localhost:8080`

## Health

`GET /api/health`

Returns radio-server status, provider name, and host config.

`GET /api/health/adapter`

Returns the netease-adapter status via the radio-server (so the web client never talks to the adapter directly). Response shape: `{"status":"ok|offline|...","mode":"mock|external-api|local-package|null"}`.

## Now Playing

`GET /api/now`

Returns:

- current item
- queue
- show title
- segment title
- `isPlaying`
- progress placeholder
- `hostLanguage`

## Controls

- `POST /api/play`
- `POST /api/pause`
- `POST /api/next`
- `POST /api/previous`

Each returns the updated now-playing state.

## Settings And Weather

`GET /api/settings`

Returns runtime settings, including optional `weatherLocation`, the latest `weather` snapshot, and integration status only. API key values are never returned.

```json
{
  "weatherLocation": "Leeds",
  "weather": null,
  "integrations": [
    { "id": "llm", "label": "LLM", "configured": true },
    { "id": "fish", "label": "Fish TTS", "configured": false },
    { "id": "netease", "label": "Netease cookie", "configured": false },
    { "id": "openweather", "label": "OpenWeather", "configured": false }
  ]
}
```

`POST /api/settings/location`

Body:

```json
{
  "location": "Leeds"
}
```

Stores the user's weather location and refreshes weather. If `OPENWEATHER_API_KEY` is set, the server uses OpenWeather geocoding and current weather; otherwise it falls back to Open-Meteo so the prototype still runs. Weather becomes recommendation context for future show planning; it is not a hard playback rule.

`POST /api/weather/refresh`

Refreshes the saved location if one exists.

## Playback Session

`POST /api/playback/clear`

Clears the active show plan and playback queue while keeping user settings such as weather location.

## Show Planning

`POST /api/plan/today`

Generates or returns today's show. v0.1 creates at least two segments, each with one English host script and a small group of tracks.

The response includes an `agentTrace` object in v0.1. It is a debug contract for the future agent layer:

```json
{
  "mode": "mock-radio-agent",
  "summary": "I read the request as mood request...",
  "contextWindow": ["taste profile: prototype fallback shelf"],
  "routing": ["chat/request -> RadioAgent"],
    "recommendationStrategy": ["Treat the first track in each segment as the chapter lead."],
  "signals": [{ "label": "intent", "value": "mood_request" }]
}
```

`POST /api/chat`

Body:

```json
{
  "message": "something quiet for late night coding"
}
```

Returns a newly planned show and updated queue. The mock planner uses the message as mood guidance.

`POST /api/agent/chat`

Body:

```json
{
  "message": "skip this"
}
```

Routes a free-form user message through the agent. The response is `{ "message": "...", "mode": "...", "shouldPlan": false, "command": "next" | "previous" | "pause" | "play" | "now" | null }`. When `shouldPlan` is true, the client may call `POST /api/chat` with the same message to materialize a new show. When `command` is set, the engine has already applied that playback action.

## Playlists

`GET /api/playlist/{id}`

Fetches normalized playlist metadata through the active `MusicProvider`.

`GET /api/lyrics/{id}`

Fetches lyrics for a track through the active `MusicProvider`. Providers may return LRC-style synced lyrics, plain text, or `null` when lyrics are unavailable.

`POST /api/import/playlist`

Body:

```json
{
  "source": "https://music.163.com/#/playlist?id=123456"
}
```

Extracts a playlist id, fetches normalized playlist metadata through the strict Netease import provider, fetches lyrics inline, ignores duplicate songs by normalized `title + artist`, and writes local private files:

- `data/taste/imports/<playlist>.raw.json`
- `data/taste/drafts/<playlist>.tagged-draft.json`
- `data/taste/lyrics/<playlist>.lyrics.json`

The import step does not trigger LLM analysis. It creates a draft with empty tags and neutral scores so the playlist can be analyzed explicitly.

Response example:

```json
{
  "slug": "netease-123456-night-shelf",
  "playlistId": "123456",
  "name": "Night Shelf",
  "importedAt": "2026-05-13T12:00:00Z",
  "trackCount": 42,
  "ignoredDuplicateCount": 0,
  "lyricsFetched": 36,
  "lyricsMissing": 6,
  "rawPath": "/.../data/taste/imports/netease-123456-night-shelf.raw.json",
  "taggedDraftPath": "/.../data/taste/drafts/netease-123456-night-shelf.tagged-draft.json",
  "lyricsPath": "/.../data/taste/lyrics/netease-123456-night-shelf.lyrics.json",
  "nextStep": "Analyze this import to write per-track evidence files."
}
```

`POST /api/import/evidence-json`

Body:

```json
{
  "sourceName": "tracks.evidence.json",
  "content": "{\"version\":2,\"tracks\":[...]}"
}
```

Imports externally analyzed evidence JSON directly into `data/taste/tracks/<provider>/<id>.json`, then rebuilds `tracks.evidence.json`. The JSON may be an object with a `tracks` array or an array of `EvidenceTrackAnalysis` objects. Tracks whose normalized title and artist already exist are ignored.

Response example:

```json
{
  "importedTrackCount": 12,
  "ignoredDuplicateCount": 3,
  "totalTrackCount": 15,
  "sourceName": "tracks.evidence.json"
}
```

`GET /api/imports`

Returns imported playlists with analysis status.

```json
[
  {
    "slug": "netease-123456-night-shelf",
    "playlistId": "123456",
    "name": "Night Shelf",
    "trackCount": 42,
    "importedAt": "2026-05-13T12:00:00Z",
    "analyzedAt": null,
    "status": "imported",
    "analyzedTrackCount": 0,
    "pendingAnalysisCount": 42
  }
]
```

`GET /api/imports/{slug}`

Returns one import plus normalized track summaries.

```json
{
  "slug": "netease-123456-night-shelf",
  "playlistId": "123456",
  "name": "Night Shelf",
  "trackCount": 42,
  "importedAt": "2026-05-13T12:00:00Z",
  "analyzedAt": null,
  "status": "imported",
  "analyzedTrackCount": 0,
  "pendingAnalysisCount": 42,
  "tracks": [
    { "provider": "netease", "id": "111", "title": "Track", "artist": "Artist" }
  ]
}
```

## Analysis Jobs

`POST /api/imports/{slug}/analyze`

Body:

```json
{
  "force": false,
  "trackIds": null
}
```

Starts an in-memory analysis job and returns immediately. `force=false` skips tracks that already have per-track evidence.

```json
{
  "jobId": "4da8...",
  "estimatedCalls": 42,
  "estimatedCostUsd": null,
  "model": "gpt-5.2"
}
```

`GET /api/jobs/{jobId}`

Returns job progress.

```json
{
  "jobId": "4da8...",
  "status": "running",
  "processed": 10,
  "total": 42,
  "current": { "provider": "netease", "id": "111", "title": "Track", "artist": "Artist" },
  "errors": [],
  "startedAt": "2026-05-13T12:00:00Z",
  "finishedAt": null
}
```

`DELETE /api/jobs/{jobId}`

Requests cancellation.

```json
{
  "jobId": "4da8...",
  "status": "cancelled",
  "processed": 10,
  "total": 42,
  "current": null,
  "errors": [],
  "startedAt": "2026-05-13T12:00:00Z",
  "finishedAt": "2026-05-13T12:01:00Z"
}
```

## Taste Library

`GET /api/taste/tracks?language=&minConfidence=&tag=&sort=&limit=&offset=`

Returns paginated UI-shaped analyzed tracks.

```json
{
  "tracks": [
    {
      "provider": "netease",
      "id": "111",
      "title": "Track",
      "artist": "Artist",
      "album": null,
      "coverUrl": null,
      "language": "en",
      "dominantTags": ["late-night"],
      "scores": { "energy": 0.4, "valence": 0.5, "night": 0.8, "coding": 0.6, "skipRisk": 0.2 },
      "confidence": 0.74,
      "needsReview": false,
      "lastAnalyzedAt": "2026-05-13T12:00:00Z"
    }
  ],
  "total": 1
}
```

`GET /api/taste/tracks/{provider}/{id}`

Returns one full `EvidenceTrackAnalysis` object from `data/taste/tracks/<provider>/<id>.json`.

`GET /api/taste/profile`

Returns current runtime taste profile text, rules, and source.

```json
{
  "profileText": "# Aftertaste FM Taste Profile\n...",
  "rules": {
    "version": 1,
    "defaultCandidateLimit": 72,
    "segmentTrackCount": 3,
    "preferredTags": [],
    "avoidTags": [],
    "moodAliases": {},
    "artistAliases": {}
  },
  "source": "data/taste"
}
```

## WebSocket Reservation

`/ws/stream`

v0.1 includes a simple now-playing snapshot stream. It is intentionally minimal and can become the realtime transport for progress and queue updates later.

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

Returns runtime settings, including optional `weatherLocation` and the latest `weather` snapshot.

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

Extracts a playlist id, fetches normalized playlist metadata through the strict Netease import provider, and writes local private files:

- `data/taste/imports/<playlist>.raw.json`
- `data/taste/drafts/<playlist>.tagged-draft.json`
- `data/taste/lyrics/<playlist>.lyrics.json`

The import step does not trigger LLM analysis. It creates a draft with empty tags and neutral scores so the playlist can be reviewed manually or analyzed offline before being promoted into `data/taste/tracks.evidence.json`.

Response fields include `rawPath`, `taggedDraftPath`, `lyricsPath`, `trackCount`, and `nextStep`.

## WebSocket Reservation

`/ws/stream`

v0.1 includes a simple now-playing snapshot stream. It is intentionally minimal and can become the realtime transport for progress and queue updates later.

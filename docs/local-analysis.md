# Local Playlist Analysis

Use this workflow when you want Codex, Claude Code, or another local agent to analyze an imported playlist outside the radio-server runtime.

## Inputs

After importing a playlist, read these private files:

- `data/taste/drafts/<slug>.tagged-draft.json`
- `data/taste/lyrics/<slug>.lyrics.json`

Listening-rank imports include `playCount` on each draft track. Treat it as user-behavior weight:
high playCount means durable familiarity, not necessarily higher energy or better fit for every moment.

Do not edit generated taste files by hand. Produce a separate JSON file, then import it from the web UI with **Import analyzed JSON**.

## Output

Write one JSON object with a `tracks` array. Each track must include the original metadata plus structured evidence.

```json
{
  "tracks": [
    {
      "provider": "netease",
      "id": "123",
      "title": "Track",
      "artist": "Artist",
      "album": null,
      "durationMs": 210000,
      "coverUrl": null,
      "playCount": 42,
      "language": { "value": "zh", "confidence": 0.9, "evidence": ["lyrics"] },
      "moodTags": [{ "tag": "reflective", "confidence": 0.8, "evidence": ["lyrics"] }],
      "contextTags": [],
      "soundTags": [],
      "useTags": [],
      "scores": {
        "energy": { "value": 0.52, "confidence": 0.66, "evidence": ["model_inference"] },
        "valence": { "value": 0.58, "confidence": 0.66, "evidence": ["lyrics"] },
        "night": { "value": 0.7, "confidence": 0.6, "evidence": ["model_inference"] },
        "coding": { "value": 0.3, "confidence": 0.5, "evidence": ["model_inference"] },
        "skipRisk": { "value": 0.2, "confidence": 0.6, "evidence": ["model_inference"] },
        "danceability": { "value": 0.45, "confidence": 0.6, "evidence": ["model_inference"] },
        "acousticness": { "value": 0.35, "confidence": 0.6, "evidence": ["model_inference"] },
        "speechiness": { "value": 0.86, "confidence": 0.66, "evidence": ["model_inference"] },
        "instrumentalness": { "value": 0.02, "confidence": 0.6, "evidence": ["model_inference"] },
        "liveness": { "value": 0.08, "confidence": 0.5, "evidence": ["model_inference"] },
        "emotionalIntensity": { "value": 0.74, "confidence": 0.66, "evidence": ["lyrics"] },
        "lyricalFocus": { "value": 0.95, "confidence": 0.66, "evidence": ["lyrics"] },
        "familiarity": { "value": 0.8, "confidence": 0.72, "evidence": ["user_behavior"] },
        "mainstreamAppeal": { "value": 0.62, "confidence": 0.55, "evidence": ["model_inference"] }
      },
      "evidence": {
        "metadata": true,
        "lyrics": true,
        "audioFeatures": false,
        "userBehavior": true,
        "manual": false,
        "model": true
      },
      "notes": "short summary",
      "analysisNotes": {
        "summary": "short paraphrased analysis",
        "evidence": [
          { "tag": "family", "evidenceString": "lyrics paraphrase a family relationship theme" }
        ]
      },
      "needsReview": false
    }
  ]
}
```

Allowed evidence source values are `metadata`, `lyrics`, `playlist_context`, `user_behavior`, and `model_inference`.

## Agent Prompt

```text
Analyze the imported playlist for Aftertaste FM.

Read the tagged draft JSON and lyrics JSON I provide. Return only one JSON object with a top-level "tracks" array. Use the exact EvidenceTrackAnalysis shape from docs/local-analysis.md. Keep provider/id/title/artist/album/durationMs/coverUrl/playCount exactly from the draft. Use playCount only as user_behavior evidence for familiarity and durable preference strength.

If your chat interface supports creating downloadable files, put the JSON into a file named aftertaste-analysis.json and attach that file instead of printing the full JSON in the chat. If file output is not available, return compact JSON text without pretty-printing.

Acceptance criteria:
- Analyze every track individually. Do not apply one playlist-level template to all tracks.
- Do not give every track the same numeric scores. Similar songs can be close, but the scores should still reflect track-level differences.
- moodTags, contextTags, soundTags, and useTags should usually contain useful track-level signals when lyrics or metadata allow it.
- speechiness means rap or spoken-word density. Sung pop ballads should usually have low speechiness unless the lyrics are delivered like rap.
- notes and analysisNotes.summary must be specific to the track, not the same repeated sentence.
- Use only the score keys shown in the schema. Do not add extra score fields.

Do not quote lyrics; paraphrase evidence. Use lowercase kebab-case tags. Prefer unknown or low confidence over pretending. Set needsReview=true when lyrics are missing, language is uncertain, or the analysis is weak.
```

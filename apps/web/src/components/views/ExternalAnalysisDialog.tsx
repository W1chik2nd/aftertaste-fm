import { Clipboard, Download, FileJson, X } from "lucide-react";
import type { ImportRecord } from "../../types";

const EXTERNAL_ANALYSIS_PROMPT = `Analyze the imported playlist for Aftertaste FM.

Inputs:
1. A tagged draft JSON file.
2. A lyrics JSON file.

Return only one valid JSON object with a top-level "tracks" array. Do not include markdown, comments, or explanations outside the JSON.

For every track, keep provider, id, title, artist, album, durationMs, coverUrl, and playCount exactly as they appear in the draft. If playCount exists, use it only as user_behavior evidence for familiarity and durable preference strength. High playCount does not automatically mean high energy, happy mood, or better fit for every moment.

Each track must use this shape:
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

Allowed evidence source values are "metadata", "lyrics", "playlist_context", "user_behavior", and "model_inference".

Use lowercase kebab-case tags. Do not quote lyrics; paraphrase evidence. Prefer unknown, empty arrays, or low confidence over pretending. Set needsReview=true when lyrics are missing, language is uncertain, or the analysis is weak.`;

type Props = {
  row: ImportRecord;
  onClose: () => void;
  onDownloadDraft: () => void;
  onDownloadLyrics: () => void;
  onError: (message: string | null) => void;
};

export function ExternalAnalysisDialog({ row, onClose, onDownloadDraft, onDownloadLyrics, onError }: Props) {
  async function copyPrompt() {
    try {
      await navigator.clipboard.writeText(EXTERNAL_ANALYSIS_PROMPT);
      onError(null);
    } catch (event) {
      console.warn("Could not copy external analysis prompt.", event);
      onError("Could not copy prompt.");
    }
  }

  return (
    <div className="dialog-backdrop" role="presentation" onMouseDown={onClose}>
      <section
        className="external-analysis-dialog"
        role="dialog"
        aria-modal="true"
        aria-labelledby="external-analysis-title"
        onMouseDown={(event) => event.stopPropagation()}
      >
        <div className="dialog-title">
          <div>
            <span>External Analysis</span>
            <h2 id="external-analysis-title">{row.name}</h2>
          </div>
          <button type="button" className="icon-button" onClick={onClose} aria-label="Close external analysis">
            <X size={18} />
          </button>
        </div>

        <div className="external-analysis-actions">
          <button type="button" className="secondary-button" onClick={onDownloadDraft}>
            <FileJson size={16} />
            Draft JSON
          </button>
          <button type="button" className="secondary-button" onClick={onDownloadLyrics}>
            <Download size={16} />
            Lyrics JSON
          </button>
          <button type="button" className="primary-button" onClick={() => void copyPrompt()}>
            <Clipboard size={16} />
            Copy prompt
          </button>
        </div>

        <textarea className="external-analysis-prompt" readOnly value={EXTERNAL_ANALYSIS_PROMPT} aria-label="External analysis prompt" />
      </section>
    </div>
  );
}

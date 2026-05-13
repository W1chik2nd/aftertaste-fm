import { Clipboard, Download, FileJson, Loader2, Upload, X } from "lucide-react";
import { useRef, useState } from "react";
import { radioApi } from "../../api";
import type { ImportEvidenceJsonResponse } from "../../externalImportTypes";
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
  onImported: () => void;
};

export function ExternalAnalysisDialog({ row, onClose, onDownloadDraft, onDownloadLyrics, onError, onImported }: Props) {
  const inputRef = useRef<HTMLInputElement | null>(null);
  const [busy, setBusy] = useState(false);
  const [pastedJson, setPastedJson] = useState("");
  const [result, setResult] = useState<ImportEvidenceJsonResponse | null>(null);

  async function copyPrompt() {
    try {
      await navigator.clipboard.writeText(EXTERNAL_ANALYSIS_PROMPT);
      onError(null);
    } catch (event) {
      console.warn("Could not copy external analysis prompt.", event);
      onError("Could not copy prompt.");
    }
  }

  async function importContent(content: string, sourceName: string) {
    const trimmed = content.trim();
    if (!trimmed) return;
    setBusy(true);
    setResult(null);
    try {
      const response = await radioApi.importEvidenceJson(trimmed, sourceName);
      setResult(response);
      setPastedJson("");
      onImported();
      onError(null);
    } catch (event) {
      onError(event instanceof Error ? event.message : "Could not import analyzed JSON.");
    } finally {
      setBusy(false);
      if (inputRef.current) inputRef.current.value = "";
    }
  }

  async function importFile(file: File) {
    try {
      await importContent(await file.text(), file.name);
    } catch (event) {
      onError(event instanceof Error ? event.message : "Could not read analyzed JSON file.");
      if (inputRef.current) inputRef.current.value = "";
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

        <div className="external-analysis-import">
          <div>
            <span>Import Result</span>
            <p>Paste the JSON text or choose the file returned by the external AI.</p>
          </div>
          <input
            ref={inputRef}
            type="file"
            accept="application/json,.json"
            onChange={(event) => {
              const file = event.currentTarget.files?.[0];
              if (file) void importFile(file);
            }}
          />
          <div className="external-analysis-import-actions">
            <button type="button" className="secondary-button" onClick={() => inputRef.current?.click()} disabled={busy}>
              <FileJson size={16} />
              Choose JSON
            </button>
            <button
              type="button"
              className="primary-button"
              onClick={() => void importContent(pastedJson, `${row.slug}.pasted-analysis.json`)}
              disabled={busy || !pastedJson.trim()}
            >
              {busy ? <Loader2 className="spin" size={16} /> : <Upload size={16} />}
              Import pasted JSON
            </button>
          </div>
          <textarea
            className="external-analysis-json"
            value={pastedJson}
            onChange={(event) => setPastedJson(event.target.value)}
            placeholder='{"tracks":[...]}'
            aria-label="Paste analyzed JSON"
          />
          {result ? (
            <p className="muted-line">
              Imported {result.importedTrackCount} tracks · ignored {result.ignoredDuplicateCount} duplicates
            </p>
          ) : null}
        </div>
      </section>
    </div>
  );
}

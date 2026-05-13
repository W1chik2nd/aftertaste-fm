import { Clipboard, Download, FileJson, X } from "lucide-react";
import type { ImportRecord } from "../../types";

const EXTERNAL_ANALYSIS_PROMPT = `Analyze the imported playlist for Aftertaste FM.

Read the tagged draft JSON and lyrics JSON I provide. Return only one JSON object with a top-level "tracks" array. Use the exact EvidenceTrackAnalysis shape from docs/local-analysis.md. Keep provider/id/title/artist/album/durationMs/coverUrl/playCount exactly from the draft. Use playCount only as user_behavior evidence for familiarity and durable preference strength. Do not quote lyrics; paraphrase evidence. Use lowercase kebab-case tags. Prefer unknown or low confidence over pretending. Set needsReview=true when lyrics are missing, language is uncertain, or the analysis is weak.`;

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

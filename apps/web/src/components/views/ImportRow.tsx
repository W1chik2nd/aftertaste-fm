import { AlertCircle, Download, FileJson, Loader2, Square, Trash2, WandSparkles } from "lucide-react";
import type { AnalysisJobView, ImportRecord } from "../../types";

type Props = {
  row: ImportRecord;
  job?: AnalysisJobView;
  busy: boolean;
  force: boolean;
  onAnalyze: () => void;
  onCancel: () => void;
  onDelete: () => void;
  onDownloadDraft: () => void;
  onDownloadLyrics: () => void;
};

export function ImportRow({ row, job, busy, force, onAnalyze, onCancel, onDelete, onDownloadDraft, onDownloadLyrics }: Props) {
  const processed = job?.processed ?? row.analyzedTrackCount;
  const total = job?.total || row.trackCount;
  const progress = total ? Math.round((processed / total) * 100) : 100;
  const running = job?.status === "running";
  const callsToRun = force ? row.trackCount : row.pendingAnalysisCount;
  const analyzeDisabled = busy || (!force && row.pendingAnalysisCount === 0);

  return (
    <article className="import-row">
      <div className="import-main">
        <div>
          <h2>{row.name}</h2>
          <p>{row.trackCount} tracks · {row.status} · {row.pendingAnalysisCount} calls pending</p>
        </div>
        <div className="import-actions">
          <button type="button" className="secondary-button" onClick={onDownloadDraft} disabled={busy} title="Download analysis draft">
            <FileJson size={15} />
            Draft
          </button>
          <button type="button" className="secondary-button" onClick={onDownloadLyrics} disabled={busy} title="Download lyrics JSON">
            <Download size={15} />
            Lyrics
          </button>
          <button type="button" className="icon-danger-button" onClick={onDelete} disabled={busy || running} aria-label={`Delete ${row.name}`}>
            <Trash2 size={16} />
          </button>
          {running ? (
            <button type="button" className="secondary-button" onClick={onCancel} disabled={busy}>
              <Square size={15} />
              Cancel
            </button>
          ) : (
            <button type="button" className="primary-button" onClick={onAnalyze} disabled={analyzeDisabled}>
              {busy ? <Loader2 className="spin" size={15} /> : <WandSparkles size={15} />}
              {force ? `Re-analyze ${callsToRun} calls` : `Analyze ${callsToRun} calls`}
            </button>
          )}
        </div>
      </div>
      <div className="meter" aria-label="Analysis progress"><span style={{ width: `${progress}%` }} /></div>
      {job?.current ? <p className="muted-line">Now analyzing {job.current.title}</p> : null}
      <JobErrors job={job} />
    </article>
  );
}

function JobErrors({ job }: { job?: AnalysisJobView }) {
  if (!job?.errors.length) return null;
  return (
    <details className="job-errors">
      <summary><AlertCircle size={15} />{job.errors.length} tracks need review</summary>
      {job.errors.map((error) => (
        <p key={`${error.trackId}-${error.message}`}>{error.trackId}: {error.message}</p>
      ))}
    </details>
  );
}

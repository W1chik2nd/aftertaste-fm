import { FormEvent, useEffect, useMemo, useState } from "react";
import { AlertCircle, Download, Loader2, Square, WandSparkles } from "lucide-react";
import { radioApi } from "../../api";
import type { AnalysisJobView, ImportPlaylistResponse, ImportRecord } from "../../types";
import { ExternalJsonImport } from "./ExternalJsonImport";

const JOB_POLL_INTERVAL_MS = 1600;

type Props = {
  onError: (message: string | null) => void;
};

export function ImportView({ onError }: Props) {
  const [source, setSource] = useState("");
  const [imports, setImports] = useState<ImportRecord[]>([]);
  const [jobsBySlug, setJobsBySlug] = useState<Record<string, AnalysisJobView>>({});
  const [loading, setLoading] = useState(true);
  const [importing, setImporting] = useState(false);
  const [importResult, setImportResult] = useState<ImportPlaylistResponse | null>(null);
  const [busySlug, setBusySlug] = useState<string | null>(null);

  const runningJobs = useMemo(
    () => Object.entries(jobsBySlug).filter(([, job]) => job.status === "running"),
    [jobsBySlug]
  );

  useEffect(() => {
    void loadImports();
  }, []);

  useEffect(() => {
    if (!runningJobs.length) return;
    const timer = window.setInterval(() => {
      void pollJobs();
    }, JOB_POLL_INTERVAL_MS);
    return () => window.clearInterval(timer);
  }, [runningJobs]);

  async function loadImports() {
    setLoading(true);
    try {
      setImports(await radioApi.imports());
      onError(null);
    } catch (event) {
      onError(event instanceof Error ? event.message : "Could not load imports.");
    } finally {
      setLoading(false);
    }
  }

  async function pollJobs() {
    const nextJobs = { ...jobsBySlug };
    let shouldRefresh = false;
    for (const [slug, job] of runningJobs) {
      try {
        const next = await radioApi.job(job.jobId);
        nextJobs[slug] = next;
        if (next.status !== "running") shouldRefresh = true;
      } catch (event) {
        onError(event instanceof Error ? event.message : "Could not refresh analysis progress.");
      }
    }
    setJobsBySlug(nextJobs);
    if (shouldRefresh) await loadImports();
  }

  async function submitImport(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const trimmed = source.trim();
    if (!trimmed) return;
    setImporting(true);
    setImportResult(null);
    try {
      const response = await radioApi.importPlaylist(trimmed);
      setImportResult(response);
      setSource("");
      await loadImports();
    } catch (event) {
      onError(event instanceof Error ? event.message : "Import failed.");
    } finally {
      setImporting(false);
    }
  }

  async function analyze(row: ImportRecord) {
    setBusySlug(row.slug);
    try {
      const started = await radioApi.analyzeImport(row.slug, { force: false, trackIds: null });
      const job = await radioApi.job(started.jobId);
      setJobsBySlug((current) => ({ ...current, [row.slug]: job }));
      onError(null);
    } catch (event) {
      onError(event instanceof Error ? event.message : "Analysis could not start.");
    } finally {
      setBusySlug(null);
    }
  }

  async function cancel(row: ImportRecord) {
    const job = jobsBySlug[row.slug];
    if (!job) return;
    setBusySlug(row.slug);
    try {
      const next = await radioApi.cancelJob(job.jobId);
      setJobsBySlug((current) => ({ ...current, [row.slug]: next }));
      await loadImports();
    } catch (event) {
      onError(event instanceof Error ? event.message : "Analysis could not be cancelled.");
    } finally {
      setBusySlug(null);
    }
  }

  return (
    <section className="view-surface import-view" aria-label="Import playlists">
      <div className="view-heading">
        <div>
          <span>Import</span>
          <h1>Bring a playlist into the taste loop.</h1>
        </div>
        <strong>{imports.length} imports</strong>
      </div>

      <form className="import-form" onSubmit={submitImport}>
        <Download size={18} />
        <input
          value={source}
          onChange={(event) => setSource(event.target.value)}
          placeholder="https://music.163.com/#/playlist?id=123456"
          aria-label="Netease playlist URL or id"
        />
        <button type="submit" disabled={importing || !source.trim()}>
          {importing ? <Loader2 className="spin" size={17} /> : <Download size={17} />}
          Import
        </button>
      </form>
      {importResult ? (
        <p className="muted-line">
          Imported {importResult.trackCount} tracks · ignored {importResult.ignoredDuplicateCount} duplicates
        </p>
      ) : null}
      <ExternalJsonImport onError={onError} onImported={() => void loadImports()} />

      <div className="import-list">
        {loading ? <p className="muted-line">Loading imports...</p> : null}
        {!loading && !imports.length ? <p className="muted-line">No imports yet.</p> : null}
        {imports.map((row) => (
          <ImportRow
            key={row.slug}
            row={row}
            job={jobsBySlug[row.slug]}
            busy={busySlug === row.slug}
            onAnalyze={() => void analyze(row)}
            onCancel={() => void cancel(row)}
          />
        ))}
      </div>
    </section>
  );
}

function ImportRow({
  row,
  job,
  busy,
  onAnalyze,
  onCancel
}: {
  row: ImportRecord;
  job?: AnalysisJobView;
  busy: boolean;
  onAnalyze: () => void;
  onCancel: () => void;
}) {
  const processed = job?.processed ?? row.analyzedTrackCount;
  const total = job?.total || row.trackCount;
  const progress = total ? Math.round((processed / total) * 100) : 100;
  const running = job?.status === "running";
  return (
    <article className="import-row">
      <div className="import-main">
        <div>
          <h2>{row.name}</h2>
          <p>
            {row.trackCount} tracks · {row.status} · {row.pendingAnalysisCount} calls pending
          </p>
        </div>
        <div className="import-actions">
          {running ? (
            <button type="button" className="secondary-button" onClick={onCancel} disabled={busy}>
              <Square size={15} />
              Cancel
            </button>
          ) : (
            <button
              type="button"
              className="primary-button"
              onClick={onAnalyze}
              disabled={busy || row.pendingAnalysisCount === 0}
            >
              {busy ? <Loader2 className="spin" size={15} /> : <WandSparkles size={15} />}
              Analyze {row.pendingAnalysisCount} calls
            </button>
          )}
        </div>
      </div>
      <div className="meter" aria-label="Analysis progress">
        <span style={{ width: `${progress}%` }} />
      </div>
      {job?.current ? <p className="muted-line">Now analyzing {job.current.title}</p> : null}
      {job?.errors.length ? (
        <details className="job-errors">
          <summary>
            <AlertCircle size={15} />
            {job.errors.length} tracks need review
          </summary>
          {job.errors.map((error) => (
            <p key={`${error.trackId}-${error.message}`}>{error.trackId}: {error.message}</p>
          ))}
        </details>
      ) : null}
    </article>
  );
}

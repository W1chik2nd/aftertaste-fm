import { FormEvent, useEffect, useMemo, useState } from "react";
import { Download, Loader2 } from "lucide-react";
import { radioApi } from "../../api";
import type { AnalysisJobView, ImportPlaylistResponse, ImportRecord } from "../../types";
import { ExternalJsonImport } from "./ExternalJsonImport";
import { ImportRow } from "./ImportRow";
import { NeteaseUserRecordImport } from "./NeteaseUserRecordImport";

const JOB_POLL_INTERVAL_MS = 1600;

type Props = {
  onError: (message: string | null) => void;
  onLibraryChanged?: () => void;
};

export function ImportView({ onError, onLibraryChanged }: Props) {
  const [source, setSource] = useState("");
  const [imports, setImports] = useState<ImportRecord[]>([]);
  const [jobsBySlug, setJobsBySlug] = useState<Record<string, AnalysisJobView>>({});
  const [loading, setLoading] = useState(true);
  const [importing, setImporting] = useState(false);
  const [recordImporting, setRecordImporting] = useState(false);
  const [importResult, setImportResult] = useState<ImportPlaylistResponse | null>(null);
  const [busySlug, setBusySlug] = useState<string | null>(null);
  const [forceReanalyze, setForceReanalyze] = useState(false);

  const hasRunningJob = useMemo(
    () => Object.values(jobsBySlug).some((job) => job.status === "running"),
    [jobsBySlug]
  );

  useEffect(() => {
    void loadImports();
  }, []);

  useEffect(() => {
    if (!hasRunningJob) return;
    const timer = window.setInterval(() => {
      void pollJobs();
    }, JOB_POLL_INTERVAL_MS);
    return () => window.clearInterval(timer);
  }, [hasRunningJob]);

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
    const running = Object.entries(jobsBySlug).filter(([, job]) => job.status === "running");
    if (!running.length) return;
    const nextJobs = { ...jobsBySlug };
    let completedAny = false;
    for (const [slug, job] of running) {
      try {
        const next = await radioApi.job(job.jobId);
        nextJobs[slug] = next;
        if (next.status !== "running") completedAny = true;
      } catch (event) {
        onError(event instanceof Error ? event.message : "Could not refresh analysis progress.");
      }
    }
    setJobsBySlug(nextJobs);
    if (completedAny) {
      await loadImports();
      onLibraryChanged?.();
    }
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

  async function importUserRecord(uid: string) {
    setRecordImporting(true);
    setImportResult(null);
    try {
      const response = await radioApi.importNeteaseUserRecord(uid);
      setImportResult(response);
      await loadImports();
    } catch (event) {
      onError(event instanceof Error ? event.message : "Listening ranking import failed.");
    } finally {
      setRecordImporting(false);
    }
  }

  async function analyze(row: ImportRecord) {
    setBusySlug(row.slug);
    try {
      const started = await radioApi.analyzeImport(row.slug, { force: forceReanalyze, trackIds: null });
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

  async function deleteImport(row: ImportRecord) {
    setBusySlug(row.slug);
    try {
      await radioApi.deleteImport(row.slug);
      await loadImports();
      onLibraryChanged?.();
      onError(null);
    } catch (event) {
      onError(event instanceof Error ? event.message : "Import could not be deleted.");
    } finally {
      setBusySlug(null);
    }
  }

  async function downloadDraft(row: ImportRecord) {
    try {
      downloadJson(`${row.slug}.tagged-draft.json`, await radioApi.importAnalysisDraft(row.slug));
      onError(null);
    } catch (event) {
      onError(event instanceof Error ? event.message : "Could not download analysis draft.");
    }
  }

  async function downloadLyrics(row: ImportRecord) {
    try {
      downloadJson(`${row.slug}.lyrics.json`, await radioApi.importLyricsFile(row.slug));
      onError(null);
    } catch (event) {
      onError(event instanceof Error ? event.message : "Could not download lyrics JSON.");
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
      <NeteaseUserRecordImport importing={recordImporting} onImport={importUserRecord} />
      {importResult ? (
        <p className="muted-line">
          Imported {importResult.trackCount} tracks · ignored {importResult.ignoredDuplicateCount} duplicates
        </p>
      ) : null}
      <ExternalJsonImport onError={onError} onImported={() => { void loadImports(); onLibraryChanged?.(); }} />

      <label className="import-force-toggle">
        <input
          type="checkbox"
          checked={forceReanalyze}
          onChange={(event) => setForceReanalyze(event.target.checked)}
        />
        Re-analyze tracks that already have evidence (force)
      </label>

      <div className="import-list">
        {loading ? <p className="muted-line">Loading imports...</p> : null}
        {!loading && !imports.length ? <p className="muted-line">No imports yet.</p> : null}
        {imports.map((row) => (
          <ImportRow
            key={row.slug}
            row={row}
            job={jobsBySlug[row.slug]}
            busy={busySlug === row.slug}
            force={forceReanalyze}
            onAnalyze={() => void analyze(row)}
            onCancel={() => void cancel(row)}
            onDelete={() => void deleteImport(row)}
            onDownloadDraft={() => void downloadDraft(row)}
            onDownloadLyrics={() => void downloadLyrics(row)}
          />
        ))}
      </div>
    </section>
  );
}

function downloadJson(filename: string, value: unknown) {
  const blob = new Blob([JSON.stringify(value, null, 2) + "\n"], { type: "application/json" });
  const url = URL.createObjectURL(blob);
  const link = document.createElement("a");
  link.href = url;
  link.download = filename;
  link.click();
  URL.revokeObjectURL(url);
}

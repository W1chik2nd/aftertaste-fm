import { useRef, useState } from "react";
import { FileJson, Loader2 } from "lucide-react";
import { radioApi } from "../../api";
import type { ImportEvidenceJsonResponse } from "../../externalImportTypes";

type Props = {
  onError: (message: string | null) => void;
  onImported: () => void;
};

export function ExternalJsonImport({ onError, onImported }: Props) {
  const inputRef = useRef<HTMLInputElement | null>(null);
  const [busy, setBusy] = useState(false);
  const [result, setResult] = useState<ImportEvidenceJsonResponse | null>(null);

  async function importFile(file: File) {
    setBusy(true);
    setResult(null);
    try {
      const content = await file.text();
      const response = await radioApi.importEvidenceJson(content, file.name);
      setResult(response);
      onImported();
      onError(null);
    } catch (event) {
      onError(event instanceof Error ? event.message : "Could not import analyzed JSON.");
    } finally {
      setBusy(false);
      if (inputRef.current) inputRef.current.value = "";
    }
  }

  return (
    <section className="external-json-import" aria-label="Import analyzed JSON">
      <input
        ref={inputRef}
        type="file"
        accept="application/json,.json"
        onChange={(event) => {
          const file = event.currentTarget.files?.[0];
          if (file) void importFile(file);
        }}
      />
      <button type="button" className="secondary-button" onClick={() => inputRef.current?.click()} disabled={busy}>
        {busy ? <Loader2 className="spin" size={16} /> : <FileJson size={16} />}
        Import analyzed JSON
      </button>
      {result ? (
        <p className="muted-line">
          Imported {result.importedTrackCount} tracks · ignored {result.ignoredDuplicateCount} duplicates
        </p>
      ) : null}
    </section>
  );
}

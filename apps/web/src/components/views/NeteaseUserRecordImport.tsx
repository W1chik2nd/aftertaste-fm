import { FormEvent, useState } from "react";
import { History, Loader2 } from "lucide-react";

type Props = {
  importing: boolean;
  onImport: (uid: string) => Promise<void>;
};

export function NeteaseUserRecordImport({ importing, onImport }: Props) {
  const [uid, setUid] = useState("");

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const trimmed = uid.trim();
    if (!trimmed) return;
    await onImport(trimmed);
    setUid("");
  }

  return (
    <form className="import-form import-form-secondary" onSubmit={submit}>
      <History size={18} />
      <input
        value={uid}
        onChange={(event) => setUid(event.target.value)}
        placeholder="Netease uid for all-time listening ranking"
        aria-label="Netease user id"
      />
      <button type="submit" disabled={importing || !uid.trim()}>
        {importing ? <Loader2 className="spin" size={17} /> : <History size={17} />}
        Import ranking
      </button>
    </form>
  );
}

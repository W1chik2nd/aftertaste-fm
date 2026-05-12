import { FormEvent } from "react";
import { CloudSun, Languages, RefreshCw, Server, Wifi } from "lucide-react";
import type { HealthResponse, SettingsResponse } from "../types";
import { weatherLabel } from "../utils/format";

type Props = {
  health: HealthResponse | null;
  adapterStatus: string;
  settings: SettingsResponse | null;
  locationInput: string;
  setLocationInput: (value: string) => void;
  onSaveLocation: (event: FormEvent<HTMLFormElement>) => void;
  busy: boolean;
};

export function StatusStrip({
  health,
  adapterStatus,
  settings,
  locationInput,
  setLocationInput,
  onSaveLocation,
  busy
}: Props) {
  return (
    <section className="settings-strip" aria-label="Settings and status">
      <StatusCell icon={<Server size={18} />} label="radio-server" value={health?.status ?? "unknown"} />
      <StatusCell icon={<Wifi size={18} />} label="provider" value={health?.provider ?? "mock"} />
      <StatusCell icon={<RefreshCw size={18} />} label="netease-adapter" value={adapterStatus} />
      <StatusCell icon={<CloudSun size={18} />} label="weather" value={weatherLabel(settings)} />
      <StatusCell icon={<Languages size={18} />} label="host language" value="English" />
      <form className="location-cell" onSubmit={onSaveLocation}>
        <CloudSun size={18} />
        <label htmlFor="weather-location">location</label>
        <input
          id="weather-location"
          value={locationInput}
          onChange={(event) => setLocationInput(event.target.value)}
          placeholder="Leeds"
        />
        <button type="submit" disabled={busy || !locationInput.trim()} title="Save weather location">
          Save
        </button>
      </form>
    </section>
  );
}

function StatusCell({ icon, label, value }: { icon: React.ReactNode; label: string; value: string }) {
  return (
    <div className="status-cell">
      {icon}
      <span>{label}</span>
      <strong>{value}</strong>
    </div>
  );
}

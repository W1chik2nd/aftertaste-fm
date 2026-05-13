import type { FormEvent, ReactNode } from "react";
import { CloudSun, KeyRound, Languages, Loader2, RefreshCw, Server, Wifi } from "lucide-react";
import type { HealthResponse, SettingsResponse } from "../../types";
import { weatherLabel } from "../../utils/format";

type Props = {
  health: HealthResponse | null;
  adapterStatus: string;
  settings: SettingsResponse | null;
  locationInput: string;
  setLocationInput: (value: string) => void;
  onSaveLocation: (event: FormEvent<HTMLFormElement>) => void;
  onRefreshStatus: () => void;
  busy: boolean;
};

export function SettingsView({
  health,
  adapterStatus,
  settings,
  locationInput,
  setLocationInput,
  onSaveLocation,
  onRefreshStatus,
  busy
}: Props) {
  return (
    <section className="view-surface settings-view" aria-label="Settings">
      <div className="view-heading">
        <div>
          <span>Settings</span>
          <h1>Station runtime and integrations.</h1>
        </div>
        <button type="button" className="secondary-button" onClick={onRefreshStatus}>
          <RefreshCw size={16} />
          Refresh
        </button>
      </div>

      <div className="settings-grid">
        <section className="settings-block">
          <h2>Host</h2>
          <Readout icon={<Languages size={18} />} label="Language" value={health?.hostConfig.hostLanguage ?? "en-US"} />
          <Readout icon={<Server size={18} />} label="Name" value={health?.hostConfig.hostName ?? "Aftertaste"} />
          <Readout icon={<Wifi size={18} />} label="Default style" value={health?.hostConfig.hostStyle ?? "calm late-night radio"} />
          <Readout icon={<CloudSun size={18} />} label="Current mode" value={health?.stationStyle.hostStyle ?? "calm late-night radio"} />
        </section>

        <section className="settings-block">
          <h2>Weather</h2>
          <Readout icon={<CloudSun size={18} />} label="Current" value={weatherLabel(settings)} />
          <form className="settings-form" onSubmit={onSaveLocation}>
            <label htmlFor="weather-location">Location</label>
            <div>
              <input
                id="weather-location"
                value={locationInput}
                onChange={(event) => setLocationInput(event.target.value)}
                placeholder="Leeds"
              />
              <button type="submit" disabled={busy || !locationInput.trim()}>
                {busy ? <Loader2 className="spin" size={16} /> : <CloudSun size={16} />}
                Save
              </button>
            </div>
          </form>
        </section>

        <section className="settings-block">
          <h2>Provider</h2>
          <Readout icon={<Wifi size={18} />} label="Music" value={health?.provider ?? "mock"} />
          <Readout icon={<RefreshCw size={18} />} label="Netease adapter" value={adapterStatus} />
          <div className="segmented-readonly" aria-label="Provider modes">
            <span className={health?.provider === "mock" ? "active" : ""}>mock</span>
            <span className={health?.provider === "netease" ? "active" : ""}>netease</span>
          </div>
        </section>

        <section className="settings-block integrations-block">
          <h2>Integrations</h2>
          {(settings?.integrations ?? []).map((item) => (
            <Readout
              key={item.id}
              icon={<KeyRound size={18} />}
              label={item.label}
              value={item.configured ? "configured" : "not set"}
            />
          ))}
        </section>
      </div>
    </section>
  );
}

function Readout({ icon, label, value }: { icon: ReactNode; label: string; value: string }) {
  return (
    <div className="settings-readout">
      {icon}
      <span>{label}</span>
      <strong>{value}</strong>
    </div>
  );
}

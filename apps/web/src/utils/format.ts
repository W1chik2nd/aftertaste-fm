import type { SettingsResponse } from "../types";

export function formatTime(value: number) {
  if (!Number.isFinite(value) || value <= 0) return "0:00";
  const minutes = Math.floor(value / 60);
  const seconds = Math.floor(value % 60);
  return `${minutes}:${seconds.toString().padStart(2, "0")}`;
}

export function weatherLabel(settings: SettingsResponse | null) {
  const weather = settings?.weather;
  if (!weather) return settings?.weatherLocation ? "waiting" : "not set";
  return `${weather.locationName} · ${weather.condition}, ${Math.round(weather.temperatureC)}C`;
}

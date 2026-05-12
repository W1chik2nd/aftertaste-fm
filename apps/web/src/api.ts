import type { AgentChatResponse, HealthResponse, PlanResponse, PlaybackState, SettingsResponse } from "./types";

const API_BASE = import.meta.env.VITE_RADIO_API_BASE ?? "http://localhost:8080";

export function resolveMediaUrl(url?: string | null) {
  if (!url) return undefined;
  if (/^https?:\/\//i.test(url)) return url;
  return `${API_BASE}${url.startsWith("/") ? url : `/${url}`}`;
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(`${API_BASE}${path}`, {
    ...init,
    headers: {
      "Content-Type": "application/json",
      ...(init?.headers ?? {})
    }
  });

  if (!response.ok) {
    throw new Error(`${response.status} ${response.statusText}`);
  }

  return response.json() as Promise<T>;
}

export const radioApi = {
  health: () => request<HealthResponse>("/api/health"),
  now: () => request<PlaybackState>("/api/now"),
  clearPlayback: () => request<PlaybackState>("/api/playback/clear", { method: "POST" }),
  settings: () => request<SettingsResponse>("/api/settings"),
  setLocation: (location: string) =>
    request<SettingsResponse>("/api/settings/location", {
      method: "POST",
      body: JSON.stringify({ location })
    }),
  refreshWeather: () => request<SettingsResponse>("/api/weather/refresh", { method: "POST" }),
  planToday: () => request<PlanResponse>("/api/plan/today", { method: "POST" }),
  chat: (message: string) =>
    request<PlanResponse>("/api/chat", {
      method: "POST",
      body: JSON.stringify({ message })
    }),
  agentChat: (message: string) =>
    request<AgentChatResponse>("/api/agent/chat", {
      method: "POST",
      body: JSON.stringify({ message })
    }),
  play: () => request<PlaybackState>("/api/play", { method: "POST" }),
  pause: () => request<PlaybackState>("/api/pause", { method: "POST" }),
  next: () => request<PlaybackState>("/api/next", { method: "POST" }),
  previous: () => request<PlaybackState>("/api/previous", { method: "POST" })
};

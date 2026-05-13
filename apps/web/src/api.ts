import type {
  AdapterHealthResponse,
  AgentChatResponse,
  AnalysisJobView,
  AnalyzeImportRequest,
  AnalyzeJobStartResponse,
  EvidenceTrackAnalysis,
  HealthResponse,
  ImportDetail,
  ImportPlaylistResponse,
  ImportRecord,
  LyricsResponse,
  PlanResponse,
  PlaybackState,
  SettingsResponse,
  TasteProfileResponse,
  TasteTagsResponse,
  TasteTracksResponse,
  DeleteImportResponse,
  DeleteTrackEvidenceResponse
} from "./types";
import type { ImportEvidenceJsonResponse } from "./externalImportTypes";

const DEFAULT_API_BASE = "";
const API_BASE = import.meta.env.VITE_RADIO_API_BASE ?? DEFAULT_API_BASE;
const ERROR_SNIPPET_CHARS = 240;

export function resolveMediaUrl(url?: string | null) {
  if (!url) return undefined;
  if (/^https?:\/\//i.test(url)) return url;
  return `${API_BASE}${url.startsWith("/") ? url : `/${url}`}`;
}

class ApiError extends Error {
  constructor(public status: number, public statusText: string, public body: string, message: string) {
    super(message);
    this.name = "ApiError";
  }
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
    let text = "";
    try {
      text = await response.text();
    } catch (event) {
      console.warn("Could not read API error body.", event);
    }
    const snippet = text.slice(0, ERROR_SNIPPET_CHARS).trim();
    const message = snippet
      ? `${response.status} ${response.statusText}: ${snippet}`
      : `${response.status} ${response.statusText}`;
    throw new ApiError(response.status, response.statusText, text, message);
  }

  return response.json() as Promise<T>;
}

export const radioApi = {
  health: () => request<HealthResponse>("/api/health"),
  adapterHealth: () => request<AdapterHealthResponse>("/api/health/adapter"),
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
  lyrics: (trackId: string) => request<LyricsResponse>(`/api/lyrics/${encodeURIComponent(trackId)}`),
  importPlaylist: (source: string) =>
    request<ImportPlaylistResponse>("/api/import/playlist", {
      method: "POST",
      body: JSON.stringify({ source })
    }),
  importEvidenceJson: (content: string, sourceName?: string) =>
    request<ImportEvidenceJsonResponse>("/api/import/evidence-json", {
      method: "POST",
      body: JSON.stringify({ content, sourceName })
    }),
  imports: () => request<ImportRecord[]>("/api/imports"),
  deleteImport: (slug: string) =>
    request<DeleteImportResponse>(`/api/imports/${encodeURIComponent(slug)}`, { method: "DELETE" }),
  importDetail: (slug: string) => request<ImportDetail>(`/api/imports/${encodeURIComponent(slug)}`),
  analyzeImport: (slug: string, body: AnalyzeImportRequest = {}) =>
    request<AnalyzeJobStartResponse>(`/api/imports/${encodeURIComponent(slug)}/analyze`, {
      method: "POST",
      body: JSON.stringify(body)
    }),
  job: (jobId: string) => request<AnalysisJobView>(`/api/jobs/${encodeURIComponent(jobId)}`),
  cancelJob: (jobId: string) =>
    request<AnalysisJobView>(`/api/jobs/${encodeURIComponent(jobId)}`, { method: "DELETE" }),
  tasteTracks: (params: Record<string, string | number | undefined | null> = {}) => {
    const query = new URLSearchParams();
    Object.entries(params).forEach(([key, value]) => {
      if (value !== undefined && value !== null && value !== "") query.set(key, String(value));
    });
    const suffix = query.toString() ? `?${query}` : "";
    return request<TasteTracksResponse>(`/api/taste/tracks${suffix}`);
  },
  tasteTrack: (provider: string, id: string) =>
    request<EvidenceTrackAnalysis>(`/api/taste/tracks/${encodeURIComponent(provider)}/${encodeURIComponent(id)}`),
  deleteTasteTrack: (provider: string, id: string) =>
    request<DeleteTrackEvidenceResponse>(
      `/api/taste/tracks/${encodeURIComponent(provider)}/${encodeURIComponent(id)}`,
      { method: "DELETE" }
    ),
  tasteTags: () => request<TasteTagsResponse>("/api/taste/tags"),
  tasteProfile: () => request<TasteProfileResponse>("/api/taste/profile"),
  play: () => request<PlaybackState>("/api/play", { method: "POST" }),
  pause: () => request<PlaybackState>("/api/pause", { method: "POST" }),
  next: () => request<PlaybackState>("/api/next", { method: "POST" }),
  previous: () => request<PlaybackState>("/api/previous", { method: "POST" })
};

export { ApiError };

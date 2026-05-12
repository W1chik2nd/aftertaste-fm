export type Track = {
  provider: string;
  id: string;
  title: string;
  artist: string;
  album?: string | null;
  durationMs?: number | null;
  coverUrl?: string | null;
  streamUrl?: string | null;
  unavailableReason?: string | null;
};

export type QueueItem = {
  id: string;
  type: "host_voice" | "track";
  segmentId?: string | null;
  segmentTitle?: string | null;
  hostName?: string | null;
  hostLanguage?: string | null;
  hostScript?: string | null;
  ttsUrl?: string | null;
  ttsCacheKey?: string | null;
  track?: Track | null;
};

export type PlaybackState = {
  currentItem?: QueueItem | null;
  currentIndex: number;
  queue: QueueItem[];
  showTitle?: string | null;
  segmentTitle?: string | null;
  isPlaying: boolean;
  progressMs: number;
  durationMs?: number | null;
  hostLanguage: string;
};

export type HealthResponse = {
  status: string;
  provider: string;
  hostConfig: {
    hostLanguage: string;
    hostStyle: string;
    hostName: string;
    segmentSpeechMode: string;
  };
  version: string;
};

export type WeatherSnapshot = {
  locationName: string;
  latitude: number;
  longitude: number;
  temperatureC: number;
  apparentTemperatureC?: number | null;
  precipitationMm?: number | null;
  weatherCode?: number | null;
  condition: string;
  windSpeedKmh?: number | null;
  fetchedAt: string;
};

export type SettingsResponse = {
  weatherLocation?: string | null;
  weather?: WeatherSnapshot | null;
};

export type LyricsResponse = {
  provider: string;
  trackId: string;
  lyrics?: string | null;
};

export type AgentTrace = {
  mode: string;
  summary: string;
  contextWindow: string[];
  routing: string[];
  recommendationStrategy: string[];
  signals: Array<{
    label: string;
    value: string;
  }>;
};

export type PlanResponse = {
  showPlan: {
    id: string;
    title: string;
    generatedAt: string;
  };
  playback: PlaybackState;
  agentTrace?: AgentTrace | null;
};

export type AgentChatResponse = {
  message: string;
  mode: string;
  shouldPlan?: boolean;
  command?: "next" | "previous" | "pause" | "play" | "now" | null;
};

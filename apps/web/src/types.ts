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

export type HostConfig = {
  hostLanguage: string;
  hostStyle: string;
  hostName: string;
  segmentSpeechMode: string;
};

export type HealthResponse = {
  status: string;
  provider: string;
  hostConfig: HostConfig;
  stationStyle: StationStyle;
  version: string;
};

export type StationStyle = {
  daypart: string;
  label: string;
  hostStyle: string;
  energyTarget: number;
  nightWeight: number;
  valenceWeight: number;
};

export type AdapterHealthResponse = {
  status: string;
  mode?: string | null;
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
  hostLanguage: string;
  integrations: IntegrationStatus[];
};

export type IntegrationStatus = {
  id: string;
  label: string;
  configured: boolean;
};

export type LyricsResponse = {
  provider: string;
  trackId: string;
  lyrics?: string | null;
};

export type AgentSignal = { label: string; value: string };

export type AgentTrace = {
  mode: string;
  summary: string;
  contextWindow: string[];
  routing: string[];
  recommendationStrategy: string[];
  signals: AgentSignal[];
};

export type ShowSegment = {
  id: string;
  title: string;
  hostScript: string;
  tracks: Track[];
};

export type ShowPlan = {
  id: string;
  title: string;
  generatedAt: string;
  hostConfig: HostConfig;
  segments: ShowSegment[];
};

export type PlanResponse = {
  showPlan: ShowPlan;
  playback: PlaybackState;
  agentTrace?: AgentTrace | null;
};

export type AgentChatResponse = {
  message: string;
  mode: string;
  shouldPlan?: boolean;
  command?: "next" | "previous" | "pause" | "play" | "now" | null;
  routingIntent?: RoutingIntent | null;
};

export type RoutingIntent = {
  language?: string | null;
  energy?: string | null;
  routine?: string | null;
  moodTag?: string | null;
  avoid: string[];
  artists: string[];
  extraTags: string[];
};

export type TrackSummary = {
  provider: string;
  id: string;
  title: string;
  artist: string;
  album?: string | null;
  durationMs?: number | null;
  coverUrl?: string | null;
  playCount?: number | null;
};

export type ImportPlaylistResponse = {
  slug: string;
  playlistId: string;
  name: string;
  importedAt: string;
  trackCount: number;
  ignoredDuplicateCount: number;
  lyricsFetched: number;
  lyricsMissing: number;
  rawPath: string;
  taggedDraftPath: string;
  lyricsPath: string;
  nextStep: string;
};

export type ImportedLyricsFile = {
  importedAt: string;
  source: string;
  playlistId: string;
  playlistName: string;
  lyricsByTrackId: Record<string, string | null>;
};

export type TaggedPlaylistDraft = {
  importedAt: string;
  source: string;
  playlistId: string;
  playlistName: string;
  tracks: Array<{
    provider: string;
    id: string;
    title: string;
    artist: string;
    album?: string | null;
    durationMs?: number | null;
    coverUrl?: string | null;
    playCount?: number | null;
    tags: string[];
    language: string;
    energy: number;
    valence: number;
    nightScore: number;
    codingScore: number;
    skipRisk: number;
    notes?: string | null;
  }>;
};

export type ImportRecord = {
  slug: string;
  playlistId: string;
  name: string;
  trackCount: number;
  importedAt: string;
  analyzedAt?: string | null;
  status: string;
  analyzedTrackCount: number;
  pendingAnalysisCount: number;
};

export type ImportDetail = ImportRecord & {
  tracks: TrackSummary[];
};

export type AnalyzeImportRequest = {
  force?: boolean;
  trackIds?: string[] | null;
};

export type AnalyzeJobStartResponse = {
  jobId: string;
  estimatedCalls: number;
  estimatedCostUsd?: number | null;
  model: string;
};

export type AnalysisJobError = {
  trackId: string;
  message: string;
};

export type AnalysisJobView = {
  jobId: string;
  status: "running" | "completed" | "cancelled" | "failed";
  processed: number;
  total: number;
  current?: TrackSummary | null;
  errors: AnalysisJobError[];
  startedAt: string;
  finishedAt?: string | null;
};

export type {
  EvidenceTag,
  EvidenceTrackAnalysis,
  TaggedTrackView,
  TasteTagsResponse,
  TasteTracksResponse
} from "./evidenceTypes";

export type TasteProfileResponse = {
  profileText: string;
  rules: TasteRules;
  source: string;
};

export type TasteRules = {
  version: number;
  defaultCandidateLimit: number;
  segmentTrackCount: number;
  preferredTags: string[];
  avoidTags: string[];
  moodAliases: Record<string, string[]>;
  artistAliases: Record<string, string[]>;
};
export type {
  DeleteImportResponse,
  DeleteTrackEvidenceResponse,
  ImportEvidenceJsonResponse
} from "./externalImportTypes";

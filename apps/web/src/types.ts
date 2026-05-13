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
  version: string;
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
};

export type TrackSummary = {
  provider: string;
  id: string;
  title: string;
  artist: string;
  album?: string | null;
  durationMs?: number | null;
  coverUrl?: string | null;
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

export type EvidenceValueString = {
  value: string;
  confidence: number;
  evidence: string[];
};

export type EvidenceValueDouble = {
  value: number;
  confidence: number;
  evidence: string[];
};

export type EvidenceTag = {
  tag: string;
  confidence: number;
  evidence: string[];
};

export type EvidenceScores = {
  energy: EvidenceValueDouble;
  valence: EvidenceValueDouble;
  night: EvidenceValueDouble;
  coding: EvidenceValueDouble;
  skipRisk: EvidenceValueDouble;
  danceability: EvidenceValueDouble;
  acousticness: EvidenceValueDouble;
  lyricDensity: EvidenceValueDouble;
  vocalPresence: EvidenceValueDouble;
  familiarity: EvidenceValueDouble;
  intensity: EvidenceValueDouble;
};

export type TrackEvidenceState = {
  metadata: boolean;
  lyrics: boolean;
  audioFeatures: boolean;
  userBehavior: boolean;
  manual: boolean;
  model: boolean;
};

export type EvidenceTrackAnalysis = {
  provider: string;
  id: string;
  title: string;
  artist: string;
  album?: string | null;
  durationMs?: number | null;
  coverUrl?: string | null;
  language: EvidenceValueString;
  moodTags: EvidenceTag[];
  contextTags: EvidenceTag[];
  soundTags: EvidenceTag[];
  useTags: EvidenceTag[];
  scores: EvidenceScores;
  evidence: TrackEvidenceState;
  lyricExcerpt?: string | null;
  notes?: string | null;
  needsReview: boolean;
  lastAnalyzedAt?: string | null;
};

export type TrackScoresView = {
  energy: number;
  valence: number;
  night: number;
  coding: number;
  skipRisk: number;
};

export type TaggedTrackView = {
  provider: string;
  id: string;
  title: string;
  artist: string;
  album?: string | null;
  coverUrl?: string | null;
  language: string;
  dominantTags: string[];
  scores: TrackScoresView;
  confidence: number;
  needsReview: boolean;
  lastAnalyzedAt?: string | null;
};

export type TasteTracksResponse = {
  tracks: TaggedTrackView[];
  total: number;
};
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
export type { ImportEvidenceJsonResponse } from "./externalImportTypes";
